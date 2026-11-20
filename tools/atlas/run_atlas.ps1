# UI-ATLAS: one command = render the atlas in Robolectric, then build the HTML + screen map.
#
#   powershell -File tools\atlas\run_atlas.ps1
#
# It mirrors what tools/gate.ps1 does for local unit tests (rsync into the WSL mirror on the
# Linux disk, run Gradle there, copy results back), so the atlas test is not slowed by /mnt/c
# and does not disturb the Windows build folder. The normal gate NEVER runs this test: the
# test class is filtered out unless `scanwhirl.atlas=true` is passed (gate stays fail-closed
# on skipped tests).
#
# Outputs (all ignored by git; the copies outside the repo are for the owner):
#   app\build\atlas\*.png + atlas.json
#   C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\screenmap\current\ATLAS.html
#   C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\screenmap\current\SCREEN_MAP_CURRENT.md
#   C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\screenmap\current\shots\*.png
#
# Options:
#   -Lane <name>       worktree folder name under ...\lanes (default: this folder's name)
#   -NoQueue           skip waiting for the shared build lock (use only when nothing else builds)
#   -OutDir <path>     where the browsable copy goes (default: HQ\qa\screenmap\current)
param(
    [string]$Lane = "",
    [switch]$NoQueue,
    [string]$OutDir = ""
)
$ErrorActionPreference = "Continue"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if (-not $Lane) { $Lane = Split-Path $root -Leaf }
# HQ root: the worktree is either HQ\current or HQ\lanes\<lane>.
$hq = Split-Path $root
if ((Split-Path $hq -Leaf) -eq "lanes") { $hq = Split-Path $hq }
$wslRoot = "/mnt/c" + (($root.Substring(2)) -replace '\\', '/')
$mirror = "/home/cybium/swbuild/$Lane"
$atlasJson = Join-Path $root "app\build\atlas\atlas.json"
$outDir = if ($OutDir) { $OutDir } else { Join-Path $hq "qa\screenmap\current" }
$oldMap = Join-Path $hq "qa\screenmap\SCREEN_MAP.md"

Write-Output "atlas: worktree $root (lane $Lane)"

# One Gradle build at a time on this PC (7.4 GB): the same lock gate.ps1 uses.
$lockDir = "C:\Users\Cybium\Desktop\ScanWhirl-HQ\locks"
$lockPath = Join-Path $lockDir "build.lock"
$holderPath = Join-Path $lockDir "holder.txt"
New-Item -ItemType Directory -Force $lockDir | Out-Null
$lock = $null
if (-not $NoQueue) {
    $started = Get-Date
    while (-not $lock) {
        try {
            $lock = [IO.File]::Open($lockPath, 'CreateNew', 'Write', 'None')
        } catch {
            try {
                $probe = [IO.File]::Open($lockPath, 'Open', 'Read', 'ReadWrite')
                $probe.Close()
                Remove-Item $lockPath -Force -ErrorAction SilentlyContinue
                continue
            } catch {}
            if (((Get-Date) - $started).TotalMinutes -ge 60) {
                Write-Output "atlas: FAIL waiting 60 min for the build slot"
                exit 2
            }
            Write-Output ("queue: waiting {0} min for the build slot ({1})" -f [int]((Get-Date) - $started).TotalMinutes, (Get-Content $holderPath -ErrorAction SilentlyContinue))
            Start-Sleep -Seconds 15
        }
    }
    Set-Content $holderPath "$Lane (atlas) since $(Get-Date -Format HH:mm)"
    Write-Output ("queue: got the build slot after {0} min" -f [int]((Get-Date) - $started).TotalMinutes)
}

# The WSL VM is ~3.5 GB (Windows host 7.4 GB; .wslconfig caps swap at 2 GB) and the renderer grows with
# every screen it draws. On 2026-11-20 the whole 136-screen atlas was kernel OOM-killed twice inside that
# VM (03:56 shot 78, 04:52 shot 93 at 3.0 GB RSS), and a third run with the heap capped stalled in swap at
# shot 93 while the host had 0.3 GB free. So the atlas is rendered in slices: each Gradle test run renders
# a contiguous block of base screens (ATLAS_SLICE, read by ScreenAtlasTest), stays near 1 GB, and
# tools/atlas/merge_atlas.py joins the slice JSONs back into atlas.json. Gradle itself is capped at
# 1024 MB (gradle.properties asks for 2048m, which left no room for the renderer).
$gradleMem = '-Xmx1024m -XX:MaxMetaspaceSize=384m'
$slices = @('0:11', '11:22', '22:32')   # baseSpecs() has 32 entries; sheets follow their base screen
$sync = "mkdir -p $mirror && rsync -a --delete --exclude=/.git --exclude=/.gradle --exclude=/.kotlin --exclude=/build --exclude=/app/build --exclude=/builds --exclude=/keystore $wslRoot/ $mirror/"
$back = "mkdir -p $wslRoot/app/build && rsync -a --delete $mirror/app/build/atlas/ $wslRoot/app/build/atlas/"
# Anything that explains a stop: compiler/lint errors, test failures, and the OOM-kill / exit-137 shapes
# that the old filter dropped on the floor.
$attention = 'error:|e: |FAILED|BUILD FAILED|What went wrong|tests completed|OutOfMemory|Killed process|Killed|non-zero exit value|Gradle Test Executor|daemon disappeared|ATLAS_GRADLE_EXIT'

$out = @()
try {
    $mi = wsl -d Ubuntu bash -lc "grep -m1 MemAvailable /proc/meminfo"
    $availMb = if ("$mi" -match '(\d+)\s*kB') { [int]([int64]$matches[1] / 1024) } else { -1 }
    Write-Output "atlas: WSL VM memory available ${availMb} MB (per slice: test JVM 1500 MB + Gradle 1024 MB)"
    if ($availMb -ge 0 -and $availMb -lt 2200) {
        Write-Output "atlas: WARN under 2200 MB free in the VM - the host is short of RAM (that is how the 03:56 and 04:52 runs died)"
    }

    # Both sides start clean: a PNG left by an aborted run must never show up as a current screenshot.
    Remove-Item -Recurse -Force (Join-Path $root "app\build\atlas") -ErrorAction SilentlyContinue
    wsl -d Ubuntu bash -lc "rm -rf $mirror/app/build/atlas" | Out-Null
    $t0 = Get-Date
    $failed = $false
    for ($i = 0; $i -lt $slices.Count; $i++) {
        $slice = $slices[$i]
        $run = "cd $mirror && ATLAS_SLICE=$slice JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/cybium/android-sdk ./gradlew :app:testDebugUnitTest --rerun -PtestBuild -PtestHeap=1500m -Dscanwhirl.atlas=true -Pscanwhirl.atlas=true -Dorg.gradle.jvmargs='$gradleMem' --no-daemon --tests '*ScreenAtlasTest*'"
        Write-Output ("atlas: slice {0}/{1} [base screens {2}] in {3}" -f ($i + 1), $slices.Count, $slice, $mirror)
        # The run prints its own verdict: exit codes did not survive wsl.exe on 2026-11-20 (they arrived empty),
        # so a Gradle failure must be read from the output, not from $LASTEXITCODE.
        $raw = wsl -d Ubuntu bash -lc "$sync && if $run; then echo ATLAS_GRADLE_EXIT=0; else echo ATLAS_GRADLE_EXIT=1; fi; $back" 2>&1
        $ok = [bool]($raw -match 'ATLAS_GRADLE_EXIT=0')
        $code = if ($ok) { 0 } else { 1 }
        $out += @($raw | Where-Object { $_ -notmatch 'screen size is bogus' })
        @($raw | Where-Object { $_ -notmatch 'screen size is bogus' }) |
            Select-String -Pattern $attention | Select-Object -First 15 | ForEach-Object { Write-Output $_.Line }
        Write-Output ("atlas: slice {0} gradle exit {1} after {2:N1} min" -f $slice, $code, ((Get-Date) - $t0).TotalMinutes)
        if (-not $ok) { $failed = $true; break }
    }

    # Copy whatever was rendered back even after a failure: the partial PNGs are the diagnosis.
    $rawBack = wsl -d Ubuntu bash -lc $back 2>&1
    $rawBack | Where-Object { $_ -notmatch 'screen size is bogus' } | ForEach-Object { Write-Output "atlas: $($_)" }
    Write-Output ("atlas: total {0:N1} min" -f ((Get-Date) - $t0).TotalMinutes)

    if (-not $failed) {
        & python (Join-Path $PSScriptRoot "merge_atlas.py") --atlas-dir (Join-Path $root "app\build\atlas") --slices $slices.Count
        if ($LASTEXITCODE -ne 0) { Write-Output "atlas: FAIL merge_atlas.py exited $LASTEXITCODE"; exit $LASTEXITCODE }
    }
} finally {
    if ($lock) { $lock.Close() }
    Remove-Item $lockPath -Force -ErrorAction SilentlyContinue
    Remove-Item $holderPath -Force -ErrorAction SilentlyContinue
}

if (-not (Test-Path $atlasJson)) {
    Write-Output "atlas: FAIL no atlas.json (the test did not run or did not finish; exit $code)"
    Write-Output "atlas: if the lines above contain 'Killed process' / 'exit value 137', the WSL VM ran out of RAM"
    $out | Select-Object -Last 30 | ForEach-Object { Write-Output "  | $_" }
    exit 1
}

$pyArgs = @(
    (Join-Path $PSScriptRoot "make_atlas.py"),
    "--atlas-dir", (Join-Path $root "app\build\atlas"),
    "--out-dir", $outDir,
    "--repo", $root
)
if (Test-Path $oldMap) { $pyArgs += @("--old-map", $oldMap) }
& python @pyArgs
$code = $LASTEXITCODE
if ($code -ne 0) { Write-Output "atlas: FAIL make_atlas.py exited $code"; exit $code }
Write-Output "atlas: DONE"
Write-Output "  open: $(Join-Path $outDir 'ATLAS.html')"
Write-Output "  map:  $(Join-Path $outDir 'SCREEN_MAP_CURRENT.md')"
exit 0
