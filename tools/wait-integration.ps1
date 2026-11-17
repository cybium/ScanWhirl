# Waits (up to 45 min) for the cloud gate run of the current integration HEAD and prints its result.
$gh = "C:\Program Files\GitHub CLI\gh.exe"
$sha = git -C C:\Users\Cybium\Desktop\ScanWhirl-HQ\current rev-parse integration
$env:GH_TOKEN = (("protocol=https`nhost=github.com`n`n" | git credential fill 2>$null) | Where-Object { $_ -like 'password=*' }) -replace '^password=', ''
$deadline = (Get-Date).AddMinutes(45); $run = $null
while ((Get-Date) -lt $deadline) {
    $runs = ((& $gh run list -R cybium/ScanWhirl --workflow gate.yml --branch integration -L 5 --json databaseId,headSha,status,conclusion) -join "`n") | ConvertFrom-Json
    $run = $runs | Where-Object { $_.headSha -eq $sha } | Select-Object -First 1
    if ($run -and $run.status -eq 'completed') { break }
    Start-Sleep 60
}
"integration $($sha.Substring(0,7)) run $($run.databaseId): $($run.status) $($run.conclusion)"
if ($run) {
    (& $gh run view $run.databaseId -R cybium/ScanWhirl --log 2>$null) |
        Select-String 'tests completed|GATE |FAILED|lint|RELEASE|errors|AAB' | Select-Object -Last 15 |
        ForEach-Object { ($_.Line -split "`t")[-1] -replace '^\S+Z\s*', '' }
}
Remove-Item Env:GH_TOKEN -ErrorAction SilentlyContinue
