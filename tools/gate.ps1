# Gate for one lane worktree, serialized across all lanes (this PC has 7.4 GB RAM: one Gradle build at a time).
# Usage:  powershell -File C:\Users\Cybium\Desktop\ScanWhirl\tools\gate.ps1 -Lane L3 [-Tests "com.scanwhirl.app.util.PdfAesTest"] [-Lint]
#   -Lane main   = the main checkout (C:\Users\Cybium\Desktop\ScanWhirl)
#   -Tests       = run only matching tests (red/green loop)
#   -Lint        = also run Android lint (slow, ~10 min; the lead runs it at merge time, lanes need not)
# The queue is first-come-first-served; it prints your place in line every minute. Waiting is normal: do not cancel.
# Prints "GATE PASS ..." or "GATE FAIL ..." as the last line. Exit code 0 = pass.
param(
    [Parameter(Mandatory = $true)][string]$Lane,
    [string]$Tests = "",
    [switch]$Lint,
    [switch]$NoLint,  # accepted for old prompts; lint is off unless -Lint
    [switch]$Apk,     # lead only: build the debug test APK (no tests) -> app\build\outputs\apk\debug\app-debug.apk
    [switch]$Local    # force the full suite on this PC instead of the cloud
)
$ErrorActionPreference = "Continue"
$root = if ($Lane -eq "main") { "C:\Users\Cybium\Desktop\ScanWhirl" } else { "C:\Users\Cybium\Desktop\ScanWhirl-$Lane" }
if (-not (Test-Path "$root\gradlew")) { Write-Output "GATE FAIL: no worktree at $root"; exit 2 }
$wslRoot = "/mnt/c" + (($root.Substring(2)) -replace '\\', '/')

# ---- cloud gate (default for the FULL suite; -Tests and -Apk stay local) ----
# Snapshots the worktree (incl. untracked, respecting .gitignore) WITHOUT touching the lane branch or index,
# pushes it to ci/<lane>, waits for the GitHub Actions "gate" run on that exact commit, prints its verdict.
if (-not $Tests -and -not $Apk -and -not $Local) {
    $gh = "C:\Program Files\GitHub CLI\gh.exe"
    $tmpIndex = Join-Path $env:TEMP ("sw-ci-index-" + $Lane + "-" + $PID)
    $env:GIT_INDEX_FILE = $tmpIndex
    git -C $root read-tree HEAD
    git -C $root add -A -- . ':!60_CORE_ENGINE_GUIDE.md' ':!LANE_BLOCKED.md' 2>$null
    # Always run the lead's current workflow, even if the lane has not merged it yet.
    $wf = "C:\Users\Cybium\Desktop\ScanWhirl\.github\workflows\gate.yml"
    $blob = git -C $root hash-object -w $wf
    git -C $root update-index --add --cacheinfo "100644,$blob,.github/workflows/gate.yml"
    $tree = git -C $root write-tree
    Remove-Item Env:GIT_INDEX_FILE; Remove-Item $tmpIndex -ErrorAction SilentlyContinue
    $parent = git -C $root rev-parse HEAD
    $commit = git -C $root commit-tree $tree -p $parent -m "ci snapshot $Lane $(Get-Date -Format o)"
    if (-not $commit) { Write-Output "GATE FAIL $Lane cloud: could not snapshot the worktree"; exit 2 }
    git -C $root push -q -f origin "${commit}:refs/heads/ci/$Lane" 2>&1 | Where-Object { $_ -notmatch '^remote:\s*$|^remote: (Create|To create|\s+https)' }
    $env:GH_TOKEN = (("protocol=https`nhost=github.com`n`n" | git credential fill 2>$null) | Where-Object { $_ -like 'password=*' }) -replace '^password=', ''
    Write-Output "cloud: snapshot $($commit.Substring(0,8)) pushed to ci/$Lane; waiting for the GitHub run (about 8-12 min, no local queue)"
    $started = Get-Date; $run = $null
    while (((Get-Date) - $started).TotalMinutes -lt 25) {
        $runs = & $gh run list -R cybium/ScanWhirl --workflow gate.yml --branch "ci/$Lane" -L 10 --json databaseId,headSha,status,conclusion 2>$null | ConvertFrom-Json
        $run = $runs | Where-Object { $_.headSha -eq $commit } | Select-Object -First 1
        if ($run -and $run.status -eq "completed") { break }
        if (-not $run -and ((Get-Date) - $started).TotalMinutes -gt 5) { Write-Output "GATE FAIL $Lane cloud: no GitHub run started for $commit"; exit 2 }
        Start-Sleep -Seconds 30
    }
    if (-not $run -or $run.status -ne "completed") { Write-Output "GATE FAIL $Lane cloud: timed out waiting for run"; exit 2 }
    $dl = Join-Path $env:TEMP ("sw-ci-result-" + $Lane + "-" + $PID); Remove-Item -Recurse -Force $dl -ErrorAction SilentlyContinue
    & $gh run download $run.databaseId -R cybium/ScanWhirl -n gate-result -D $dl 2>$null | Out-Null
    $resFile = Join-Path $dl "gate-result.txt"
    $minutes = [int]((Get-Date) - $started).TotalMinutes
    if (Test-Path $resFile) {
        $lines = Get-Content $resFile
        $lines | Select-Object -SkipLast 1 | Select-Object -First 30 | ForEach-Object { Write-Output "  $_" }
        $verdict = ($lines | Select-Object -Last 1) -replace '^GATE (PASS|FAIL)', "GATE `$1 $Lane"
        Write-Output "cloud: run $($run.databaseId) finished in $minutes min (https://github.com/cybium/ScanWhirl/actions/runs/$($run.databaseId))"
        Remove-Item -Recurse -Force $dl -ErrorAction SilentlyContinue; Remove-Item Env:GH_TOKEN
        Write-Output $verdict
        if ($verdict -match '^GATE PASS') { exit 0 } else { exit 1 }
    }
    Remove-Item Env:GH_TOKEN
    Write-Output "GATE FAIL $Lane cloud: run $($run.databaseId) ended '$($run.conclusion)' with no result file (build error?) https://github.com/cybium/ScanWhirl/actions/runs/$($run.databaseId)"
    exit 1
}

# ---- queue ----
$lockDir = "C:\Users\Cybium\Desktop\.sw-locks"; New-Item -ItemType Directory -Force $lockDir | Out-Null
$lock = Join-Path $lockDir "build.lock"
$holderFile = Join-Path $lockDir "holder.txt"
$qDir = Join-Path $lockDir "queue"; New-Item -ItemType Directory -Force $qDir | Out-Null
# FIFO ticket: <ticks>_<lane>_<pid>.t ; tickets of dead processes are discarded.
$ticket = Join-Path $qDir ("{0:D20}_{1}_{2}.t" -f (Get-Date).Ticks, $Lane, $PID)
New-Item -ItemType File -Force $ticket | Out-Null
$held = $null; $waited = 0; $started = Get-Date
try {
while (-not $held) {
    $live = @(Get-ChildItem $qDir -Filter *.t | Sort-Object Name | Where-Object {
        $p = [int]($_.BaseName.Split('_')[-1])
        if (Get-Process -Id $p -ErrorAction SilentlyContinue) { $true } else { Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue; $false }
    })
    $pos = [Array]::IndexOf(@($live | ForEach-Object { $_.FullName }), $ticket)
    if ($pos -le 0) {
        try { $held = [IO.File]::Open($lock, 'CreateNew', 'Write', 'None'); break } catch {
            # A lock nobody holds open is left over from a killed gate: remove it.
            try { $probe = [IO.File]::Open($lock, 'Open', 'Read', 'ReadWrite'); $probe.Close(); Remove-Item $lock -Force -ErrorAction SilentlyContinue; continue } catch {}
        }
    }
    if ($waited % 60 -eq 0) {
        $ahead = if ($pos -gt 0) { ($live[0..($pos - 1)] | ForEach-Object { $_.BaseName.Split('_')[1] }) -join ', ' } else { "-" }
        Write-Output ("queue: waiting {0} min, place {1} in line; building now: {2}; ahead of you: {3}. Waiting is normal, do not cancel." -f `
            [int]((Get-Date) - $started).TotalMinutes, ($pos + 1), (Get-Content $holderFile -ErrorAction SilentlyContinue), $ahead)
    }
    Start-Sleep -Seconds 10; $waited += 10
}
} finally { Remove-Item $ticket -Force -ErrorAction SilentlyContinue }
Set-Content $holderFile "$Lane since $(Get-Date -Format HH:mm)"
try {
    Write-Output "queue: got build slot for $Lane after $([int]((Get-Date) - $started).TotalMinutes) min"
    Remove-Item -Recurse -Force "$root\app\build\test-results\testDebugUnitTest" -ErrorAction SilentlyContinue
    # Build from a mirror on the Linux disk (~/swbuild/<lane>): reading/writing /mnt/c from WSL is several times slower.
    # The mirror keeps its own build folder, so builds stay incremental and never mix with Windows (lint) outputs.
    $mirror = "/home/cybium/swbuild/$Lane"
    $filter = if ($Tests) { "--tests '$Tests'" } else { "" }
    $sync = "mkdir -p $mirror && rsync -a --delete --exclude=/.git --exclude=/.gradle --exclude=/.kotlin --exclude=/build --exclude=/app/build --exclude=/builds --exclude=/keystore $wslRoot/ $mirror/ && rm -rf $mirror/app/build/test-results/testDebugUnitTest"
    if ($Apk) {
        $apkOut = "$root\app\build\outputs\apk\debug\app-debug.apk"
        Remove-Item $apkOut -Force -ErrorAction SilentlyContinue
        $acmd = "$sync && cd $mirror && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/cybium/android-sdk ./gradlew :app:assembleDebug -PtestBuild --no-daemon -q && mkdir -p $wslRoot/app/build/outputs/apk/debug && cp $mirror/app/build/outputs/apk/debug/app-debug.apk $wslRoot/app/build/outputs/apk/debug/app-debug.apk"
        $aStart = Get-Date
        $aout = wsl -d Ubuntu bash -lc $acmd 2>&1 | Where-Object { $_ -notmatch 'screen size is bogus' }
        $aout | Select-String -Pattern 'error:|e: |FAILED|What went wrong' | Select-Object -First 30 | ForEach-Object { Write-Output $_.Line }
        Write-Output ("apk: build took {0:N1} min" -f ((Get-Date) - $aStart).TotalMinutes)
        if (Test-Path $apkOut) { Write-Output "APK READY $apkOut"; exit 0 } else { Write-Output "APK FAIL"; exit 1 }
    }
    $back = "mkdir -p $wslRoot/app/build/test-results && rsync -a --delete $mirror/app/build/test-results/testDebugUnitTest/ $wslRoot/app/build/test-results/testDebugUnitTest/"
    $cmd = "$sync && cd $mirror && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/cybium/android-sdk ./gradlew :app:testDebugUnitTest -PtestBuild --no-daemon --continue -q $filter; $back"
    Write-Output "tests: building in WSL mirror $mirror $filter"
    $buildStart = Get-Date
    $out = wsl -d Ubuntu bash -lc $cmd 2>&1 | Where-Object { $_ -notmatch 'screen size is bogus' }
    Write-Output ("tests: build+run took {0:N1} min" -f ((Get-Date) - $buildStart).TotalMinutes)
    $out | Select-String -Pattern 'error:|e: |FAILED|BUILD FAILED|What went wrong' | Select-Object -First 40 | ForEach-Object { Write-Output $_.Line }

    $t = 0; $f = 0; $s = 0; $failed = @()
    Get-ChildItem "$root\app\build\test-results\testDebugUnitTest\*.xml" -ErrorAction SilentlyContinue | ForEach-Object {
        [xml]$x = Get-Content $_.FullName
        $t += [int]$x.testsuite.tests; $f += [int]$x.testsuite.failures + [int]$x.testsuite.errors; $s += [int]$x.testsuite.skipped
        $x.testsuite.testcase | Where-Object { $_.failure -or $_.error } | ForEach-Object {
            $msg = if ($_.failure) { $_.failure.message } else { $_.error.message }
            $failed += "  FAIL $($_.classname.Split('.')[-1]) > $($_.name) :: $((($msg -split "`n")[0]))"
        }
    }
    $failed | Select-Object -First 30 | ForEach-Object { Write-Output $_ }
    Write-Output "tests: total=$t failures=$f skipped=$s"
    if ($t -eq 0) {
        # No results at all: show the tail of the Gradle output so the cause is visible.
        Write-Output "---- gradle output tail (no test results) ----"
        $out | Select-Object -Last 40 | ForEach-Object { Write-Output "  | $_" }
    }
    $ok = ($t -gt 0 -and $f -eq 0 -and $s -eq 0)

    $lintMsg = "lint skipped (lead runs it at merge)"
    if ($ok -and -not $Tests -and $Lint) {
        Push-Location $root
        & .\gradlew.bat lintDebug -PtestBuild --no-daemon --rerun-tasks -q 2>&1 | Out-Null
        Pop-Location
        $rep = Get-ChildItem "$root\app\build" -Recurse -Filter lint-results-debug.txt -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        $lintMsg = if ($rep) { (Get-Content $rep.FullName -Tail 1) } else { "lint report missing" }
        if ($lintMsg -notmatch '^0 errors') { $ok = $false }
    }
    if ($ok) { Write-Output "GATE PASS $Lane tests=$t lint=[$lintMsg]"; exit 0 }
    else { Write-Output "GATE FAIL $Lane tests=$t failures=$f skipped=$s lint=[$lintMsg]"; exit 1 }
} finally {
    if ($held) { $held.Close() }
    Remove-Item $lock, $holderFile -Force -ErrorAction SilentlyContinue
}
