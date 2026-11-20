<#
Real-Android E2E: push a worktree snapshot to ci/e2e-<name> and print the run URL.

Mirrors the cloud path of tools/gate.ps1:
  * snapshots the worktree (tracked + untracked, respecting .gitignore) into a
    throwaway commit WITHOUT touching the branch or the index;
  * screens the snapshot with tools/ci/prepush_guard.py before it becomes public
    (the repository is public);
  * force-pushes it to refs/heads/ci/e2e-<name> and waits for the "e2e" workflow.

It can never push to integration: the only ref it writes is ci/e2e-<name>.

Usage:
  powershell -File tools/e2e/run_e2e.ps1 -Lane e1emulator
  powershell -File tools/e2e/run_e2e.ps1 -Lane e1emulator -Name integration
  powershell -File tools/e2e/run_e2e.ps1 -Lane main -Name integration -NoWait

  -Lane  lane worktree under ScanWhirl-HQ\lanes\<Lane>; "main" = the checked-out
         ScanWhirl-HQ\current tree.
  -Name  ci branch suffix (default: the lane name) -> ci/e2e-<Name>.
  -NoWait push and print the URL without waiting for the result.
#>
param(
    [Parameter(Mandatory = $true)][string]$Lane,
    [string]$Name = "",
    [switch]$NoWait,
    [int]$TimeoutMinutes = 45
)
$ErrorActionPreference = "Continue"
$root = if ($Lane -eq "main") { "C:\Users\Cybium\Desktop\ScanWhirl-HQ\current" } else { "C:\Users\Cybium\Desktop\ScanWhirl-HQ\lanes\$Lane" }
if (-not (Test-Path "$root\gradlew")) { Write-Output "E2E FAIL: no worktree at $root"; exit 2 }
if (-not $Name) { $Name = $Lane }
$ciBranch = "ci/e2e-$Name"
if (-not $ciBranch.StartsWith("ci/e2e-")) { Write-Output "E2E FAIL: refusing branch $ciBranch"; exit 2 }

# --- snapshot the worktree (never to integration) ---------------------------------
$tmpIndex = Join-Path $env:TEMP ("sw-e2e-index-" + $Lane + "-" + $PID)
$env:GIT_INDEX_FILE = $tmpIndex
git -C $root read-tree HEAD
git -C $root add -A -- . ':!60_CORE_ENGINE_GUIDE.md' ':!LANE_BLOCKED.md' 2>$null
# Snapshot the selected lane's harness as well as its app. Substituting scripts
# from another checkout silently tests different code and blocks harness lanes.
$tree = git -C $root write-tree
Remove-Item Env:GIT_INDEX_FILE; Remove-Item $tmpIndex -ErrorAction SilentlyContinue
$parent = git -C $root rev-parse HEAD
$commit = git -C $root commit-tree $tree -p $parent -m "e2e snapshot $Name $(Get-Date -Format o)"
if (-not $commit) { Write-Output "E2E FAIL: could not snapshot the worktree"; exit 2 }

# --- public-repo guard -------------------------------------------------------------
$guardPy = Join-Path $root "tools\ci\prepush_guard.py"
if (-not (Test-Path $guardPy)) { Write-Output "E2E FAIL: pre-push guard missing ($guardPy)"; exit 2 }
& python $guardPy --repo $root --commit $commit
if ($LASTEXITCODE -ne 0) { Write-Output "E2E FAIL: pre-push guard blocked the snapshot; nothing pushed"; exit 2 }

git -C $root push -q -f origin "${commit}:refs/heads/$ciBranch" 2>&1 |
    Where-Object { $_ -notmatch '^remote:\s*$|^remote: (Create|To create|\s+https)' }
Write-Output "e2e: snapshot $($commit.Substring(0,8)) (from $Lane) pushed to $ciBranch"

$gh = "C:\Program Files\GitHub CLI\gh.exe"
if (-not (Test-Path $gh)) { $gh = "gh" }
$env:GH_TOKEN = (("protocol=https`nhost=github.com`n`n" | git credential fill 2>$null) | Where-Object { $_ -like 'password=*' }) -replace '^password=', ''

# --- find the run (URL first, so a -NoWait call is still useful) ---------------------
$started = Get-Date
$run = $null
while (((Get-Date) - $started).TotalMinutes -lt 5) {
    $runs = & $gh run list -R cybium/ScanWhirl --workflow e2e.yml --branch "$ciBranch" -L 10 --json databaseId,headSha,status,conclusion 2>$null | ConvertFrom-Json
    $run = $runs | Where-Object { $_.headSha -eq $commit } | Select-Object -First 1
    if ($run) { break }
    Start-Sleep -Seconds 10
}
if (-not $run) {
    Remove-Item Env:GH_TOKEN
    Write-Output "E2E FAIL: GitHub did not start an e2e run for $commit on $ciBranch"
    exit 2
}
$url = "https://github.com/cybium/ScanWhirl/actions/runs/$($run.databaseId)"
Write-Output "e2e: run $($run.databaseId) $url"
if ($NoWait) { Remove-Item Env:GH_TOKEN; exit 0 }

while ($run.status -ne "completed" -and ((Get-Date) - $started).TotalMinutes -lt $TimeoutMinutes) {
    Start-Sleep -Seconds 30
    $runs = & $gh run list -R cybium/ScanWhirl --workflow e2e.yml --branch "$ciBranch" -L 10 --json databaseId,headSha,status,conclusion 2>$null | ConvertFrom-Json
    $run = $runs | Where-Object { $_.headSha -eq $commit } | Select-Object -First 1
}
$minutes = [int]((Get-Date) - $started).TotalMinutes
if (-not $run -or $run.status -ne "completed") {
    Remove-Item Env:GH_TOKEN
    Write-Output "E2E FAIL: timed out after $minutes min waiting for $url"
    exit 2
}
# Per-leg breakdown straight from the run (the API level lives in the job name).
& $gh run view $run.databaseId -R cybium/ScanWhirl --json jobs --jq '.jobs[] | "  \(.name) \(.conclusion)"' 2>$null |
    ForEach-Object { Write-Output $_ }
Remove-Item Env:GH_TOKEN
Write-Output "e2e: run finished in $minutes min, conclusion '$($run.conclusion)' $url"
if ($run.conclusion -eq "success") { Write-Output "E2E PASS $Name"; exit 0 }
Write-Output "E2E FAIL $Name (run concluded '$($run.conclusion)')"
exit 1
