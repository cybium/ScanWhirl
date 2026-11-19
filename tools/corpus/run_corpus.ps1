# Private corpus run. The regular gate never opts in. No corpus bytes enter the mirror.
param([string]$CorpusPath = "")
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$lane = Split-Path $root -Leaf
$hq = if ($lane -eq "current") { Split-Path $root } else { Split-Path (Split-Path $root) }
if (-not $CorpusPath) { $CorpusPath = Join-Path $hq "qa\corpus" }
$corpus = (Resolve-Path $CorpusPath).Path
if (($corpus + '\').StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "Corpus must be outside the Git worktree"
}
if (-not (Test-Path (Join-Path $corpus "TOOL_MATRIX.md"))) { throw "No TOOL_MATRIX.md in $corpus" }
function WslPath($path) {
    if ($path -notmatch '^[Cc]:\\' -or $path.Contains("'")) { throw "Expected a local C: path without apostrophes" }
    return "/mnt/c" + ($path.Substring(2) -replace '\\', '/')
}
if ($lane -notmatch '^[a-zA-Z0-9-]+$') { throw "Unsupported lane name" }
$wslRoot = WslPath $root
$wslCorpus = WslPath $corpus
$results = Join-Path $corpus "RESULTS"
$wslResults = WslPath $results
$mirror = "/home/cybium/swbuild/$lane"

# Same FIFO tickets and exclusive build.lock as tools/gate.ps1. Never bypass.
$lockDir = Join-Path $hq "locks"
$queue = Join-Path $lockDir "queue"
New-Item -ItemType Directory -Force $queue | Out-Null
$lockPath = Join-Path $lockDir "build.lock"
$holder = Join-Path $lockDir "holder.txt"
$ticket = Join-Path $queue ("{0:D20}_{1}_{2}.t" -f (Get-Date).Ticks, $lane, $PID)
New-Item -ItemType File $ticket | Out-Null
$held = $null
try {
    while (-not $held) {
        $live = @(Get-ChildItem $queue -Filter *.t | Sort-Object Name | Where-Object {
            $ownerPid = [int]($_.BaseName.Split('_')[-1])
            if (Get-Process -Id $ownerPid -ErrorAction SilentlyContinue) { $true }
            else { Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue; $false }
        })
        if ($live.Count -and $live[0].FullName -eq $ticket) {
            try { $held = [IO.File]::Open($lockPath, 'CreateNew', 'Write', 'None') }
            catch {
                try {
                    $probe = [IO.File]::Open($lockPath, 'Open', 'Read', 'ReadWrite')
                    $probe.Close()
                    Remove-Item $lockPath -Force -ErrorAction SilentlyContinue
                    continue
                } catch {}
            }
        }
        if (-not $held) {
            Write-Output "queue: corpus waiting for shared build slot"
            Start-Sleep -Seconds 15
        }
    }
    Set-Content $holder "$lane (corpus) since $(Get-Date -Format HH:mm)"
    Remove-Item $ticket -Force
    New-Item -ItemType Directory -Force $results | Out-Null
    $revision = (git -C $root rev-parse HEAD).Trim()
    if (git -C $root status --porcelain) { $revision += "-dirty" }
    $runStatus = Join-Path $results "run-status.txt"
    Set-Content $runStatus "Running $revision since $(Get-Date -Format o); earlier reports are not a result for this run."
    $sync = "mkdir -p '$mirror' && rsync -a --delete --exclude=/.git --exclude=/.gradle --exclude=/.kotlin --exclude=/build --exclude=/app/build --exclude=/builds --exclude=/keystore --exclude=/local.properties --exclude=/keystore.properties '$wslRoot/' '$mirror/'"
    $run = "cd '$mirror' && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/cybium/android-sdk ./gradlew :app:testDebugUnitTest -PtestBuild -PtestHeap=2300m -Dscanwhirl.corpus=true '-Dscanwhirl.corpus.path=$wslCorpus' '-Dscanwhirl.corpus.out=$wslResults' '-Dscanwhirl.corpus.revision=$revision' --no-daemon --tests '*CorpusToolsTest*'"
    # Keep the Gradle exit code (verification failures are deliberately nonzero).
    $ErrorActionPreference = "Continue"
    wsl -d Ubuntu bash -lc "$sync && $run" 2>&1 | Tee-Object -FilePath (Join-Path $results "gradle.log")
    $rc = $LASTEXITCODE
    Set-Content $runStatus "Gradle exited $rc for $revision at $(Get-Date -Format o); verify report provenance and completion."
    Write-Output "corpus: Gradle exit $rc; reports in $results (check revision and completion)"
    exit $rc
} finally {
    Remove-Item $ticket -Force -ErrorAction SilentlyContinue
    if ($held) {
        Remove-Item $holder -Force -ErrorAction SilentlyContinue
        $held.Close()
        Remove-Item $lockPath -Force -ErrorAction SilentlyContinue
    }
}
