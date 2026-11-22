<#
.SYNOPSIS
  Trigger the CI demo-apk workflow, wait for it, download the artifact and verify its SHA256.
.EXAMPLE
  .\tools\demo_apk.ps1 -Ref integration -Out C:\ScanWhirl\demo-apks
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Ref,
    [Parameter(Mandatory = $true)]
    [string]$Out,
    # Branch that holds the workflow definition (the lead commits/pushes to integration).
    [string]$WorkflowRef = 'integration'
)

$ErrorActionPreference = 'Stop'
$Repo = 'cybium/ScanWhirl'
$Workflow = 'demo-apk.yml'
$PollSeconds = 30
$TimeoutMinutes = 40

function Gh {
    param([string[]]$GhArgs)
    # PS 5.1 turns native stderr into a terminating error under ErrorActionPreference=Stop;
    # gh is silent on success, but keep this wrapper safe and check the exit code ourselves.
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & gh @GhArgs 2>$null
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $old
    }
    if ($code -ne 0) { throw "gh $($GhArgs -join ' ') failed (exit $code)" }
    return $out
}

# workflow_dispatch needs the workflow on the default branch (master stays untouched), so the
# commit to build is pushed as apk/<shortsha>; the workflow's push trigger builds exactly it.
$sha = (git rev-parse "$Ref^{commit}").Trim()
if ($LASTEXITCODE -ne 0 -or -not $sha) { throw "Unknown ref $Ref" }
$short = $sha.Substring(0, 8)
git push -q -f origin "${sha}:refs/heads/apk/$short" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "push to apk/$short failed" }

$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$runId = $null
while (-not $runId) {
    if ((Get-Date) -ge $deadline) { throw 'Timed out waiting for the demo-apk run to appear' }
    Start-Sleep -Seconds $PollSeconds
    try {
        $runId = Gh @('run', 'list', '--repo', $Repo, '--workflow', $Workflow, '--branch', "apk/$short",
            '--limit', '5', '--json', 'databaseId,headSha', '--jq', "[.[] | select(.headSha==`"$sha`")][0].databaseId // empty")
    } catch { $runId = $null }
}

$conclusion = ''
while ($true) {
    if ((Get-Date) -ge $deadline) { throw "Timed out waiting for demo-apk run $runId" }
    try {
        $line = Gh @('run', 'view', $runId, '--repo', $Repo, '--json', 'status,conclusion',
            '--jq', '[.status, (.conclusion // "")] | @tsv')
    } catch { Start-Sleep -Seconds $PollSeconds; continue }  # transient network errors (TLS timeouts seen)
    $parts = ("$line" -split "`t")
    $status = $parts[0]
    $conclusion = if ($parts.Count -gt 1) { $parts[1] } else { '' }
    if ($status -eq 'completed') { break }
    Start-Sleep -Seconds $PollSeconds
}
if ($conclusion -ne 'success') { throw "demo-apk run $runId finished with '$conclusion'" }

if (-not (Test-Path -LiteralPath $Out)) {
    New-Item -ItemType Directory -Path $Out -Force | Out-Null
}
$dest = (Resolve-Path -LiteralPath $Out).Path
Gh @('run', 'download', $runId, '--repo', $Repo, '--pattern', 'demo-apk-*', '--dir', $dest) | Out-Null

$shaFiles = @(Get-ChildItem -LiteralPath $dest -Recurse -File -Filter 'SHA256.txt')
if ($shaFiles.Count -eq 0) { throw 'SHA256.txt not found in the downloaded artifact' }
$shaFile = $shaFiles[0]
$expected = ((Get-Content -LiteralPath $shaFile.FullName -TotalCount 1) -split '\s+')[0]
$apk = Join-Path $shaFile.Directory.FullName 'app-debug.apk'
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw 'app-debug.apk not found next to SHA256.txt' }
$actual = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected.ToLowerInvariant()) { throw "SHA256 mismatch: expected $expected, got $actual" }

git push -q origin --delete "apk/$short" 2>&1 | Out-Null
Write-Output "READY demo-apk commit=$short run=$runId sha256=$actual path=$apk"
