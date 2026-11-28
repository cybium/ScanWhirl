<#
.SYNOPSIS
  Fetch CircleCI atlas artefacts for a commit or branch, or report credit usage.

.DESCRIPTION
  Usage: pwsh tools/ci/circleci_fetch.ps1 <branch-or-sha> <outDir>
         pwsh tools/ci/circleci_fetch.ps1 -Credits [-Ref <branch>]

  Finds the pipeline for a cc/atlas-* branch or a commit SHA in the public
  project gh/cybium/ScanWhirl through CircleCI API v2. The public project is
  readable without a token; if CircleCI starts asking for authentication, the
  script reads a personal read-only token from CIRCLE_TOKEN (process env first,
  then User scope) and sends it ONLY as the Circle-Token request header. The
  token is never printed, logged or written to disk.

  Each atlas leg's artefacts are stored under <outDir>/<leg>/ (light, dark,
  font200, small24), including index.html, report.json and numbered PNG/XML.

  -Credits prints credits used by each workflow on a branch (and per atlas job)
  plus a month-to-date (last-30-days Insights) total across cc/atlas-* branches.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $false, Position = 0)]
    [string]$Ref,
    [Parameter(Mandatory = $false, Position = 1)]
    [string]$OutDir,
    [switch]$Credits
)

$ErrorActionPreference = 'Stop'
$Project = 'gh/cybium/ScanWhirl'
$ApiBase = 'https://circleci.com/api/v2'
$script:Token = $null
$script:Legs = @('light', 'dark', 'font200', 'small24')

function Resolve-CircleToken {
    $value = $env:CIRCLE_TOKEN
    if (-not $value) {
        $value = [Environment]::GetEnvironmentVariable('CIRCLE_TOKEN', 'User')
    }
    return $value
}

function Get-HttpStatus {
    param($ErrorRecord)
    try {
        if ($ErrorRecord.Exception.Response) {
            return [int]$ErrorRecord.Exception.Response.StatusCode
        }
    } catch { }
    return 0
}

function Invoke-CircleApi {
    param([string]$Uri)

    $headers = @{ 'Accept' = 'application/json' }
    if ($script:Token) { $headers['Circle-Token'] = $script:Token }
    try {
        return Invoke-RestMethod -Uri $Uri -Headers $headers -TimeoutSec 60
    } catch {
        $status = Get-HttpStatus -ErrorRecord $_
        if (($status -eq 401 -or $status -eq 403) -and -not $script:Token) {
            $envToken = Resolve-CircleToken
            if (-not $envToken) {
                throw "CircleCI API returned $status for $Uri and requires a token. Set the read-only token in CIRCLE_TOKEN (process or User scope) and re-run; nothing was written."
            }
            Write-Host 'CircleCI API requires authentication; using CIRCLE_TOKEN from the environment.'
            $script:Token = $envToken
            $headers['Circle-Token'] = $script:Token
            return Invoke-RestMethod -Uri $Uri -Headers $headers -TimeoutSec 60
        }
        throw
    }
}

function Get-Paged {
    param([string]$Uri)
    $items = @()
    $next = $null
    $pages = 0
    do {
        $pageUri = $Uri
        if ($next) { $pageUri += '&page-token=' + [uri]::EscapeDataString($next) }
        $page = Invoke-CircleApi -Uri $pageUri
        foreach ($item in $page.items) { $items += $item }
        $next = $page.next_page_token
        $pages++
    } while ($next -and $pages -lt 20)
    return $items
}

function Find-Pipeline {
    param([string]$Wanted)
    $isSha = $Wanted -match '^[0-9a-fA-F]{7,40}$'
    if ($isSha) {
        $wanted = $Wanted.ToLowerInvariant()
        $pipelines = Get-Paged -Uri "$ApiBase/project/$Project/pipeline"
        $match = $pipelines | Where-Object {
            $_.vcs -and $_.vcs.revision -and $_.vcs.revision.ToLowerInvariant().StartsWith($wanted)
        } | Select-Object -First 1
        if (-not $match) {
            throw "No CircleCI pipeline found in $Project for commit $Wanted. Push it to a cc/atlas-* branch first."
        }
        return $match
    }

    $encoded = [uri]::EscapeDataString($Wanted)
    $pipelines = Get-Paged -Uri "$ApiBase/project/$Project/pipeline?branch=$encoded"
    $match = $pipelines | Where-Object { $_.vcs -and $_.vcs.branch -eq $Wanted } | Select-Object -First 1
    if (-not $match) {
        throw "No CircleCI pipeline found in $Project for branch $Wanted. Use a cc/atlas-* branch that has been pushed."
    }
    return $match
}

function Format-InsightsDate {
    param([DateTime]$Value)
    return $Value.ToString('yyyy-MM-ddTHH:mm:ssZ', [Globalization.CultureInfo]::InvariantCulture)
}

function Get-JobCreditsTimeSeries {
    param([string]$Branch, [DateTime]$Start, [DateTime]$End)
    $uri = "$ApiBase/insights/time-series/$Project/workflows/atlas/jobs?branch=" +
           [uri]::EscapeDataString($Branch) + '&granularity=daily&start-date=' +
           [uri]::EscapeDataString((Format-InsightsDate $Start)) + '&end-date=' +
           [uri]::EscapeDataString((Format-InsightsDate $End))
    $result = @()
    foreach ($row in (Get-Paged -Uri $uri)) {
        $result += [pscustomobject]@{
            name = $row.name
            credits = [int]$row.metrics.total_credits_used
            runs = [int]$row.metrics.total_runs
        }
    }
    return $result
}

function Show-Credits {
    param([string]$Branch)
    $now = [DateTime]::UtcNow
    $windows = @(
        @{ Name = 'last-24-hours'; Label = 'last 24 hours'; Start = $now.AddHours(-24) },
        @{ Name = 'last-30-days'; Label = 'last 30 days'; Start = $now.AddDays(-30) }
    )
    # The aggregated workflows endpoint can lag behind the time-series endpoint:
    # use it first, fall back to per-job time-series when it has no rows yet.
    foreach ($window in $windows) {
        Write-Host "`nCredits ($($window.Label)):"
        $uri = "$ApiBase/insights/$Project/workflows?reporting-window=$($window.Name)"
        if ($Branch) { $uri += '&branch=' + [uri]::EscapeDataString($Branch) }
        else { $uri += '&all-branches=true' }
        $rows = @(Get-Paged -Uri $uri)
        if ($rows.Count -eq 0 -and $Branch) {
            $rows = @(Get-JobCreditsTimeSeries -Branch $Branch -Start $window.Start -End $now)
        }
        foreach ($row in $rows) {
            $runs = [int]$row.metrics.total_runs
            $credits = [int]$row.metrics.total_credits_used
            $perRun = if ($runs -gt 0) { [math]::Round($credits / $runs, 1) } else { 0 }
            Write-Host ("  {0}: {1} credits total, {2} runs, ~{3}/run" -f $row.name, $credits, $runs, $perRun)
        }
    }

    if ($Branch) {
        Write-Host "`nCredits per atlas job ($Branch, last 24 hours):"
        $rows = @(Get-JobCreditsTimeSeries -Branch $Branch -Start $now.AddHours(-24) -End $now)
        foreach ($row in $rows) {
            $perRun = if ($row.runs -gt 0) { [math]::Round($row.credits / $row.runs, 1) } else { 0 }
            Write-Host ("  {0}: {1} credits total, {2} runs, ~{3}/run" -f $row.name, $row.credits, $row.runs, $perRun)
        }
    }

    # Insights has no "branch prefix" filter, so sum per-branch job time series.
    Write-Host "`nMonth-to-date credits across cc/atlas-* branches (Insights time series):"
    $monthStart = [DateTime]::UtcNow.Date.AddDays(1 - [DateTime]::UtcNow.Day)
    try {
        $branchInfo = Invoke-CircleApi -Uri "$ApiBase/insights/$Project/branches?workflow-name=atlas"
        $atlasBranches = @($branchInfo.branches | Where-Object { $_ -like 'cc/atlas-*' } | Select-Object -First 50)
    } catch {
        $atlasBranches = @()
    }
    if ($atlasBranches.Count -eq 0) { $atlasBranches = @('cc/atlas-circleci-test') }
    $monthTotal = 0
    foreach ($b in $atlasBranches) {
        $credits = ($rows2 = @(Get-JobCreditsTimeSeries -Branch $b -Start $monthStart -End $now) |
                    Measure-Object -Property credits -Sum).Sum
        if (-not $credits) { $credits = 0 }
        $monthTotal += $credits
        Write-Host ("  {0}: {1} credits" -f $b, $credits)
    }
    Write-Host "  TOTAL: $monthTotal credits (Insights is approximate, not billing-grade)."
}

if ($Credits) {
    Show-Credits -Branch $Ref
    return
}

if (-not $Ref -or -not $OutDir) {
    throw 'Usage: circleci_fetch.ps1 <branch-or-sha> <outDir>  (or -Credits [-Ref <branch>])'
}

$pipeline = Find-Pipeline -Wanted $Ref
$pipelineId = $pipeline.id
$pipelineUrl = "https://app.circleci.com/pipelines/github/cybium/ScanWhirl/$($pipeline.number)/workflows"
Write-Host "Pipeline $($pipeline.number): $pipelineUrl"
Write-Host "Commit: $($pipeline.vcs.revision)  Branch: $($pipeline.vcs.branch)"

$workflows = Get-Paged -Uri "$ApiBase/pipeline/$pipelineId/workflow"
$workflow = $workflows | Where-Object { $_.name -eq 'atlas' } | Select-Object -First 1
if (-not $workflow) { $workflow = $workflows | Select-Object -First 1 }
if (-not $workflow) { throw "No workflows found for CircleCI pipeline $pipelineId." }
Write-Host "Workflow: $($workflow.name) ($($workflow.status))"

$jobs = Get-Paged -Uri "$ApiBase/workflow/$($workflow.id)/job"
$totalFiles = 0
foreach ($leg in $script:Legs) {
    $job = $jobs | Where-Object { $_.name -eq "atlas-$leg" -and $_.job_number } | Select-Object -First 1
    if (-not $job) {
        Write-Warning "No atlas-$leg job in workflow $($workflow.id); skipping."
        continue
    }
    $legDir = Join-Path $OutDir $leg
    New-Item -ItemType Directory -Force -Path $legDir | Out-Null

    $artifacts = Get-Paged -Uri "$ApiBase/project/$Project/$($job.job_number)/artifacts"
    if (-not $artifacts -or $artifacts.Count -eq 0) {
        Write-Warning "No artifacts for job atlas-$leg (#$($job.job_number))."
        continue
    }

    $count = 0
    foreach ($artifact in $artifacts) {
        $artifactPath = [string]$artifact.path
        $normalized = $artifactPath -replace '\\', '/'
        $parts = $normalized.Split('/')
        $legIndex = [Array]::IndexOf($parts, $leg)
        if ($legIndex -ge 0) {
            # Artefact paths look like atlas/<leg>/<file>; keep only what follows <leg>.
            $start = $legIndex + 1
            while ($start -lt $parts.Length -and $parts[$start] -eq $leg) { $start++ }
            if ($start -ge $parts.Length) { continue }
            $relative = ($parts[$start..($parts.Length - 1)] -join '/')
        } else {
            $relative = $parts[-1]
        }
        $relative = $relative -replace '/', [IO.Path]::DirectorySeparatorChar
        if ($relative.Split([IO.Path]::DirectorySeparatorChar) -contains '..') {
            Write-Warning "Skipping unsafe artifact path: $artifactPath"
            continue
        }
        $target = Join-Path $legDir $relative
        $parent = Split-Path -Parent $target
        if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }

        $url = [string]$artifact.url
        if ($url -notmatch '^https?://') { $url = 'https://' + $url }
        $dlHeaders = @{}
        if ($script:Token) { $dlHeaders['Circle-Token'] = $script:Token }
        try {
            Invoke-WebRequest -Uri $url -Headers $dlHeaders -OutFile $target -TimeoutSec 120 -UseBasicParsing
        } catch {
            if ($dlHeaders.Count -gt 0) {
                # Artefact CDN links are usually public; retry without the token.
                Invoke-WebRequest -Uri $url -OutFile $target -TimeoutSec 120 -UseBasicParsing
            } else { throw }
        }
        $count++
    }
    $totalFiles += $count
    $report = Join-Path $legDir 'report.json'
    if (Test-Path $report) {
        $data = Get-Content -Raw -Encoding UTF8 $report | ConvertFrom-Json
        Write-Host ("{0}: {1} files, {2} screens (API {3}, {4} min)" -f $leg, $count, $data.totals.screens, $data.api, $data.elapsed_minutes)
    } else {
        Write-Host ("{0}: {1} files, no report.json" -f $leg, $count)
    }
}

Write-Host "Done. $totalFiles artefact(s) downloaded to $([IO.Path]::GetFullPath($OutDir))."
