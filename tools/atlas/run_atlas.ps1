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
$hq = Split-Path (Split-Path $root)
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

try {
    Remove-Item -Recurse -Force (Join-Path $root "app\build\atlas") -ErrorAction SilentlyContinue
    $sync = "mkdir -p $mirror && rsync -a --delete --exclude=/.git --exclude=/.gradle --exclude=/.kotlin --exclude=/build --exclude=/app/build --exclude=/builds --exclude=/keystore $wslRoot/ $mirror/"
    $run = "cd $mirror && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/cybium/android-sdk ./gradlew :app:testDebugUnitTest -PtestBuild -PtestHeap=2300m -Dscanwhirl.atlas=true -Pscanwhirl.atlas=true --no-daemon --tests '*ScreenAtlasTest*'"
    $back = "mkdir -p $wslRoot/app/build && rsync -a --delete $mirror/app/build/atlas/ $wslRoot/app/build/atlas/"
    Write-Output "atlas: rendering in $mirror (Gradle; about 5-10 min)"
    $t0 = Get-Date
    $out = wsl -d Ubuntu bash -lc "$sync && $run; $back" 2>&1 |
        Where-Object { $_ -notmatch 'screen size is bogus' }
    $out | Select-String -Pattern 'error:|e: |FAILED|BUILD FAILED|What went wrong|tests completed' |
        Select-Object -First 30 | ForEach-Object { Write-Output $_.Line }
    Write-Output ("atlas: gradle took {0:N1} min" -f ((Get-Date) - $t0).TotalMinutes)
} finally {
    if ($lock) { $lock.Close() }
    Remove-Item $lockPath -Force -ErrorAction SilentlyContinue
    Remove-Item $holderPath -Force -ErrorAction SilentlyContinue
}

if (-not (Test-Path $atlasJson)) {
    Write-Output "atlas: FAIL no atlas.json (the test did not run or did not finish)"
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
