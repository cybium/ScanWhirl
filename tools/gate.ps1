# Gate for one lane worktree, serialized across all lanes (this PC has 7.4 GB RAM: one Gradle build at a time).
# Usage:  powershell -File C:\Users\Cybium\Desktop\ScanWhirl\tools\gate.ps1 -Lane L3 [-Tests "com.scanwhirl.app.util.PdfAesTest"] [-NoLint]
#   -Lane main   = the main checkout (C:\Users\Cybium\Desktop\ScanWhirl)
#   -Tests       = run only matching tests (red/green loop); lint is skipped automatically
# Prints "GATE PASS ..." or "GATE FAIL ..." as the last line. Exit code 0 = pass.
param(
    [Parameter(Mandatory = $true)][string]$Lane,
    [string]$Tests = "",
    [switch]$NoLint
)
$ErrorActionPreference = "Continue"
$root = if ($Lane -eq "main") { "C:\Users\Cybium\Desktop\ScanWhirl" } else { "C:\Users\Cybium\Desktop\ScanWhirl-$Lane" }
if (-not (Test-Path "$root\gradlew")) { Write-Output "GATE FAIL: no worktree at $root"; exit 2 }
$wslRoot = "/mnt/c" + (($root.Substring(2)) -replace '\\', '/')

# ---- queue ----
$lockDir = "C:\Users\Cybium\Desktop\.sw-locks"; New-Item -ItemType Directory -Force $lockDir | Out-Null
$lock = Join-Path $lockDir "build.lock"
$held = $null; $waited = 0
while (-not $held) {
    try {
        $held = [IO.File]::Open($lock, 'CreateNew', 'Write', 'None')
        $w = New-Object IO.StreamWriter($held); $w.Write("$Lane $(Get-Date -Format o)"); $w.Flush()
    } catch {
        $age = (Get-Date) - (Get-Item $lock -ErrorAction SilentlyContinue).LastWriteTime
        if ($age.TotalMinutes -gt 120) { Remove-Item $lock -Force -ErrorAction SilentlyContinue; continue }
        if ($waited % 60 -eq 0) { Write-Output "queue: waiting for build slot (held by: $(Get-Content $lock -ErrorAction SilentlyContinue))" }
        Start-Sleep -Seconds 10; $waited += 10
    }
}
try {
    Write-Output "queue: got build slot for $Lane"
    # A Windows build leaves Windows paths in these folders; the Linux build then fails with an NPE.
    foreach ($d in "mergeDebugResources", "packageDebugResources") {
        Remove-Item -Recurse -Force "$root\app\build\intermediates\incremental\debug\$d" -ErrorAction SilentlyContinue
    }
    Remove-Item -Recurse -Force "$root\app\build\test-results\testDebugUnitTest" -ErrorAction SilentlyContinue

    $filter = if ($Tests) { "--tests '$Tests'" } else { "" }
    $cmd = "cd $wslRoot && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/cybium/android-sdk ./gradlew :app:testDebugUnitTest -PtestBuild --no-daemon --continue -q $filter"
    Write-Output "tests: $cmd"
    $out = wsl -d Ubuntu bash -lc $cmd 2>&1
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
    $ok = ($t -gt 0 -and $f -eq 0 -and $s -eq 0)

    $lintMsg = "lint skipped"
    if ($ok -and -not $Tests -and -not $NoLint) {
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
    Remove-Item $lock -Force -ErrorAction SilentlyContinue
}
