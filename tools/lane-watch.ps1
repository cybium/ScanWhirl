# Lead watchdog: one screen with every sign that a lane is stuck or making a mess.
# Usage: powershell -File tools\lane-watch.ps1        (no secrets printed; gh token only in memory)
$ErrorActionPreference = 'Continue'
$now = Get-Date
"=== lane watch {0:HH:mm:ss}" -f $now
$warn = @()

# Every lane worktree (ScanWhirl-<lane>), not just L1-L5: night lanes are named data/ui/pro/... .
# Lanes whose branch is fully merged and idle > 6 h are listed once, without warnings.
$lanes = @(git -C C:\Users\Cybium\Desktop\ScanWhirl-HQ\current worktree list --porcelain 2>$null |
    Where-Object { $_ -match '^worktree (.+[/\\]ScanWhirl-HQ[/\\]lanes[/\\]([^/\\]+))$' } | ForEach-Object { $Matches[2] })
$idle = @()
foreach ($n in $lanes) {
    $p = "C:\Users\Cybium\Desktop\ScanWhirl-HQ\lanes\$n"
    if (-not (Test-Path $p)) { continue }
    $ahead = [int](git -C $p rev-list --count "integration..HEAD" 2>$null)
    $lastTouch = (Get-ChildItem "$p\app\src", "$p\docs", "$p\tools" -Recurse -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
    if ($ahead -eq 0 -and $lastTouch -and ($now - $lastTouch).TotalHours -gt 6) { $idle += $n; continue }
    $f = Get-ChildItem "$p\app\src", "$p\docs", "$p\tools" -Recurse -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $editMin = if ($f) { [int]($now - $f.LastWriteTime).TotalMinutes } else { 9999 }
    $cTime = git -C $p log -1 --format=%ct 2>$null
    $commitMin = if ($cTime) { [int]($now - ([DateTimeOffset]::FromUnixTimeSeconds([long]$cTime).LocalDateTime)).TotalMinutes } else { 9999 }
    $subject = (git -C $p log -1 --format=%s 2>$null)
    if ($subject.Length -gt 70) { $subject = $subject.Substring(0, 70) + '...' }
    $dirty = @(git -C $p status --porcelain 2>$null)
    $unmerged = @(git -C $p diff --name-only --diff-filter=U 2>$null)
    $behind = (git -C $p rev-list --count "HEAD..integration" 2>$null)
    $branch = (git -C $p rev-parse --abbrev-ref HEAD 2>$null)
    "{0,-6} {1,-22} edit {2,4}m ({3}) | commit {4,4}m | dirty {5,3} | ahead {6} behind {7}" -f `
        $n, $branch, $editMin, $(if ($f) { $f.Name } else { '-' }), $commitMin, $dirty.Count, $ahead, $behind
    "       commit: $subject"
    if ($editMin -ge 30) { $warn += "$n no file edits for $editMin min (stuck or waiting on a long gate?)" }
    if ($commitMin -ge 90 -and $dirty.Count -gt 0) { $warn += "$n has $($dirty.Count) uncommitted files and no commit for $commitMin min" }
    if ($unmerged.Count) { $warn += "$n has UNRESOLVED merge conflicts: $($unmerged -join ', ')" }
    if ($dirty.Count -gt 40) { $warn += "$n has $($dirty.Count) uncommitted files (possible mess)" }
    $stray = $dirty | Where-Object { $_ -match '\.(orig|rej|bak)$|hs_err_pid|\.swbackup$|keystore' }
    if ($stray) { $warn += "$n stray files: $($stray -join '; ')" }
}
if ($idle.Count) { "idle (merged, untouched > 6 h): " + ($idle -join ', ') }

"--- gate processes"
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
    Where-Object { $_.CommandLine -match '^"?\S*powershell\S*"? +-File \S*gate\.ps1' } | ForEach-Object {
        $age = [int]($now - $_.CreationDate).TotalMinutes
        "  {0,3} min  {1}" -f $age, ($_.CommandLine -replace '.*gate.ps1', 'gate')
        if ($age -ge 40) { $warn += "gate process running $age min: $($_.CommandLine -replace '.*gate.ps1','gate')" }
    }
$lock = "C:\Users\Cybium\Desktop\ScanWhirl-HQ\locks"
"  build slot: " + (Get-Content "$lock\holder.txt" -ErrorAction SilentlyContinue)
Get-ChildItem "$lock\queue" -Filter *.t -ErrorAction SilentlyContinue | ForEach-Object {
    $parts = $_.BaseName.Split('_'); $age = [int]($now - [datetime][long]$parts[0]).TotalMinutes
    "  queued: {0} ({1} min)" -f $parts[1], $age
    if ($age -ge 20) { $warn += "$($parts[1]) waiting in the local queue $age min" }
}
"--- eval flags"
Get-ChildItem C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\owner, C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\realworld -Filter *EVAL_ON -ErrorAction SilentlyContinue |
    ForEach-Object { "  {0} ({1} min old)" -f $_.Name, [int]($now - $_.LastWriteTime).TotalMinutes
        if ($_.Name -eq 'EVAL_ON') { $warn += "shared EVAL_ON flag exists (lanes must use <LANE>_EVAL_ON)" } }

"--- cloud runs (last 60 min)"
$gh = "C:\Program Files\GitHub CLI\gh.exe"
$env:GH_TOKEN = (("protocol=https`nhost=github.com`n`n" | git credential fill 2>$null) | Where-Object { $_ -like 'password=*' }) -replace '^password=', ''
$json = (& $gh run list -R cybium/ScanWhirl --workflow gate.yml -L 40 --json headBranch,status,conclusion,createdAt,databaseId 2>$null) -join "`n"
Remove-Item Env:GH_TOKEN -ErrorAction SilentlyContinue
if ($json) {
    $runs = $json | ConvertFrom-Json
    $recent = @($runs | Where-Object { ([datetime]$_.createdAt).ToLocalTime() -gt $now.AddMinutes(-60) })
    $recent | Group-Object headBranch | ForEach-Object {
        $fails = @($_.Group | Where-Object { $_.conclusion -eq 'failure' }).Count
        $live = @($_.Group | Where-Object { $_.status -ne 'completed' }).Count
        "  {0,-14} runs {1,2}  failed {2,2}  running {3}" -f $_.Name, $_.Count, $fails, $live
        $last4 = @($_.Group | Sort-Object createdAt -Descending | Select-Object -First 4)
        if ($last4.Count -eq 4 -and @($last4 | Where-Object { $_.conclusion -eq 'failure' }).Count -eq 4) { $warn += "$($_.Name): last 4 cloud runs all failed (looping on a red gate? lane full gates may fail only on the known TextFitTest)" }
    }
    $runs | Where-Object { $_.status -ne 'completed' -and ([datetime]$_.createdAt).ToLocalTime() -lt $now.AddMinutes(-30) } |
        ForEach-Object { $warn += "cloud run $($_.databaseId) on $($_.headBranch) running > 30 min" }
}

"--- phone"
$adbx = "C:\Users\Cybium\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$dev = (& $adbx devices 2>$null | Select-String '103223137I112880\s+device')
if (-not $dev) { "  phone NOT connected"; if (Test-Path "C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\PHONE_BUSY") { $warn += "phone disconnected while PHONE_BUSY exists" } }
else {
    $win = & $adbx -s 103223137I112880 shell dumpsys window 2>$null
    $locked = [bool]($win | Select-String 'isKeyguardShowing=true')
    "  locked: $locked | focus: " + (($win | Select-String 'mCurrentFocus') -join ' ').Trim()
    if ($locked) { $warn += "phone is LOCKED (phone worker blocked)" }
}
"  PHONE_BUSY: " + (Get-Content "C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\PHONE_BUSY" -ErrorAction SilentlyContinue)
$hunt = Get-ChildItem "C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\hunt", "C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\screenmap" -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '_aborted' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($hunt) { $hm = [int]($now - $hunt.LastWriteTime).TotalMinutes; "  phone worker last output: $hm min ago ($($hunt.Name))"
    if ($hm -ge 30 -and (Test-Path "C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\PHONE_BUSY")) { $warn += "phone worker produced nothing for $hm min" } }

"--- integration"
"  " + (git -C C:\Users\Cybium\Desktop\ScanWhirl-HQ\current log -1 --format="%h %s" integration)

"=== WARNINGS: $($warn.Count)"
$warn | ForEach-Object { "  ! $_" }
