# Run adb commands while holding the single phone slot (one lane on the phone at a time).
# Usage:
#   powershell -File C:\Users\Cybium\Desktop\ScanWhirl\tools\phone.ps1 -Lane L2 -Run 'adbp install -r app\build\outputs\apk\debug\app-debug.apk; adbp shell am start -n com.scanwhirl.app.test/com.scanwhirl.app.ui.MainActivity'
# Inside -Run, use `adbp <args>`: adb already bound to the Infinix (serial 103223137I112880).
param([Parameter(Mandatory = $true)][string]$Lane, [Parameter(Mandatory = $true)][string]$Run, [int]$MaxMinutes = 20)
$lockDir = "C:\Users\Cybium\Desktop\.sw-locks"; New-Item -ItemType Directory -Force $lockDir | Out-Null
$lock = Join-Path $lockDir "phone.lock"; $held = $null
while (-not $held) {
    try { $held = [IO.File]::Open($lock, 'CreateNew', 'Write', 'None') }
    catch {
        $age = (Get-Date) - (Get-Item $lock -ErrorAction SilentlyContinue).LastWriteTime
        if ($age.TotalMinutes -gt $MaxMinutes + 10) { Remove-Item $lock -Force -ErrorAction SilentlyContinue; continue }
        Write-Output "phone: waiting for the phone slot"; Start-Sleep -Seconds 15
    }
}
try {
    function adbp { & "C:\Users\Cybium\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s 103223137I112880 @args }
    Write-Output "phone: slot held by $Lane"
    Invoke-Expression $Run
} finally { $held.Close(); Remove-Item $lock -Force -ErrorAction SilentlyContinue; Write-Output "phone: released" }
