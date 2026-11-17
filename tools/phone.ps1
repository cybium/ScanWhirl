# Lead phone helper (dot-source it):  . .\tools\phone.ps1
# Screens and recordings go to C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\shots (private, never committed).
$script:adb = "C:\Users\Cybium\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$script:ser = "103223137I112880"
$script:shots = "C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\shots"
$script:pkg = "com.scanwhirl.app.test"
New-Item -ItemType Directory -Force $script:shots | Out-Null

function A { & $script:adb -s $script:ser @args }
function Sh([string]$cmd) { & $script:adb -s $script:ser shell $cmd }

function Wake {
    Sh "input keyevent KEYCODE_WAKEUP"; Sh "wm dismiss-keyguard" | Out-Null
}

function Shot([string]$name) {
    $f = Join-Path $script:shots ("{0}_{1:HHmmss}.png" -f $name, (Get-Date))
    # PowerShell 5 pipes are text: go through a file on the device (byte-exact).
    Sh "screencap -p /sdcard/sw_shot.png" | Out-Null
    A pull /sdcard/sw_shot.png $f 2>&1 | Out-Null
    Sh "rm /sdcard/sw_shot.png" | Out-Null
    $f
}

function Tap([int]$x, [int]$y) { Sh "input tap $x $y" | Out-Null }
function Swipe([int]$x1, [int]$y1, [int]$x2, [int]$y2, [int]$ms = 300) { Sh "input swipe $x1 $y1 $x2 $y2 $ms" | Out-Null }
function LongPressDrag([int]$x1, [int]$y1, [int]$x2, [int]$y2) { Sh "input draganddrop $x1 $y1 $x2 $y2 1500" | Out-Null }
function Back { Sh "input keyevent KEYCODE_BACK" | Out-Null }
function TypeText([string]$t) { Sh ("input text '" + ($t -replace ' ', '%s') + "'") | Out-Null }

# UI tree: every node with text / content-desc / resource-id and its centre.
function Nodes {
    Sh "uiautomator dump /sdcard/sw_ui.xml" | Out-Null
    $xml = Sh "cat /sdcard/sw_ui.xml"
    $doc = [xml]($xml -join '')
    $doc.SelectNodes('//node') | ForEach-Object {
        if ($_.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            [pscustomobject]@{
                text = $_.text; desc = $_.'content-desc'; id = ($_.'resource-id' -replace '.*:id/', '')
                cx = [int](([int]$matches[1] + [int]$matches[3]) / 2); cy = [int](([int]$matches[2] + [int]$matches[4]) / 2)
                w = [int]$matches[3] - [int]$matches[1]; h = [int]$matches[4] - [int]$matches[2]
                click = $_.clickable; enabled = $_.enabled
            }
        }
    }
}
function Find([string]$pattern) { Nodes | Where-Object { $_.text -match $pattern -or $_.desc -match $pattern -or $_.id -match $pattern } }
function TapOn([string]$pattern) {
    $n = Find $pattern | Select-Object -First 1
    if (-not $n) { throw "not on screen: $pattern" }
    Tap $n.cx $n.cy; $n
}

function StartApp { Sh "monkey -p $script:pkg -c android.intent.category.LAUNCHER 1" | Out-Null }
function StopApp { Sh "am force-stop $script:pkg" | Out-Null }

# Screen recording (max 180 s). Start, act, then Stop-Rec to pull the mp4.
function Start-Rec([string]$name, [int]$sec = 30) {
    $script:recName = $name
    Start-Process -WindowStyle Hidden -FilePath $script:adb -ArgumentList "-s $($script:ser) shell screenrecord --time-limit $sec --bit-rate 8000000 /sdcard/sw_rec.mp4"
    Start-Sleep -Milliseconds 800
}
function Stop-Rec {
    Sh "pkill -INT screenrecord" | Out-Null
    Start-Sleep 2
    $f = Join-Path $script:shots ("{0}_{1:HHmmss}.mp4" -f $script:recName, (Get-Date))
    A pull /sdcard/sw_rec.mp4 $f 2>&1 | Out-Null
    Sh "rm /sdcard/sw_rec.mp4" | Out-Null
    $f
}
