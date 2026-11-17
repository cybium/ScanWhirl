# Lead heartbeat: sleep N minutes, then print the lane watch. Run it in the BACKGROUND; when it ends,
# the lead is woken by the completion notice, reads the report, acts, and starts the next heartbeat.
param([int]$Minutes = 30)
Start-Sleep -Seconds ($Minutes * 60)
powershell -NoProfile -File "$PSScriptRoot\lane-watch.ps1"
