# Lead watchdog: one screen with every sign that a lane is stuck or making a mess.
# Usage: powershell -File tools\lane-watch.ps1        (no secrets printed; gh token only in memory)
$ErrorActionPreference = 'Continue'
$now = Get-Date
"=== lane watch {0:HH:mm:ss}" -f $now
$warn = @()

foreach ($n in 1..5) {
    $p = "C:\Users\Cybium\Desktop\ScanWhirl-L$n"
    if (-not (Test-Path $p)) { continue }
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
    "L{0}: last edit {1,4} min ago ({2}) | last commit {3,4} min ago | uncommitted {4,3} | behind integration {5}" -f `
        $n, $editMin, $(if ($f) { $f.Name } else { '-' }), $commitMin, $dirty.Count, $behind
    "     commit: $subject"
    if ($editMin -ge 30) { $warn += "L$n no file edits for $editMin min (stuck or waiting on a long gate?)" }
    if ($commitMin -ge 90 -and $dirty.Count -gt 0) { $warn += "L$n has $($dirty.Count) uncommitted files and no commit for $commitMin min" }
    if ($unmerged.Count) { $warn += "L$n has UNRESOLVED merge conflicts: $($unmerged -join ', ')" }
    if ($dirty.Count -gt 40) { $warn += "L$n has $($dirty.Count) uncommitted files (possible mess)" }
    $stray = $dirty | Where-Object { $_ -match '\.(orig|rej|bak)$|hs_err_pid|\.swbackup$|keystore' }
    if ($stray) { $warn += "L$n stray files: $($stray -join '; ')" }
}

"--- gate processes"
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
    Where-Object { $_.CommandLine -match '^"?\S*powershell\S*"? +-File \S*gate\.ps1' } | ForEach-Object {
        $age = [int]($now - $_.CreationDate).TotalMinutes
        "  {0,3} min  {1}" -f $age, ($_.CommandLine -replace '.*gate.ps1', 'gate')
        if ($age -ge 40) { $warn += "gate process running $age min: $($_.CommandLine -replace '.*gate.ps1','gate')" }
    }
$lock = "C:\Users\Cybium\Desktop\.sw-locks"
"  build slot: " + (Get-Content "$lock\holder.txt" -ErrorAction SilentlyContinue)
Get-ChildItem "$lock\queue" -Filter *.t -ErrorAction SilentlyContinue | ForEach-Object {
    $parts = $_.BaseName.Split('_'); $age = [int]($now - [datetime][long]$parts[0]).TotalMinutes
    "  queued: {0} ({1} min)" -f $parts[1], $age
    if ($age -ge 20) { $warn += "$($parts[1]) waiting in the local queue $age min" }
}
"--- eval flags"
Get-ChildItem C:\Users\Cybium\Desktop\.sw-qa\owner, C:\Users\Cybium\Desktop\.sw-qa\realworld -Filter *EVAL_ON -ErrorAction SilentlyContinue |
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

"--- integration"
"  " + (git -C C:\Users\Cybium\Desktop\ScanWhirl log -1 --format="%h %s" integration)

"=== WARNINGS: $($warn.Count)"
$warn | ForEach-Object { "  ! $_" }
