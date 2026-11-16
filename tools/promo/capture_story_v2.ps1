# Record approved demo-app shots only, after the build owner releases the phone.
# Run from PowerShell; footage remains outside git.
$ErrorActionPreference = 'Stop'
$lock = 'C:\Users\Cybium\Desktop\.sw-qa\PHONE_BUSY'
if ((Get-Content $lock -Raw).Trim() -ne 'terra: recording') { throw 'Phone lock not owned by this capture.' }
. "$PSScriptRoot\..\phone.ps1"
$script:pkg = 'com.scanwhirl.app.demo'
$raw = 'C:\Users\Cybium\Desktop\.sw-qa\promo_v2\raw_final'
New-Item -ItemType Directory -Force $raw | Out-Null

function Begin-StoryClip([string]$name, [int]$seconds) {
    if ((Get-Content $lock -Raw).Trim() -ne 'terra: recording') { throw 'Phone lock ownership changed.' }
    $script:clipName = $name
    $script:clip = Start-Process -PassThru -WindowStyle Hidden -FilePath $script:adb -ArgumentList "-s $script:ser shell screenrecord --time-limit $seconds --bit-rate 12000000 /sdcard/sw_clip.mp4"
    Start-Sleep -Seconds 1
}
function End-StoryClip {
    $script:clip.WaitForExit()
    A pull /sdcard/sw_clip.mp4 (Join-Path $raw "$script:clipName.mp4")
    Sh 'rm /sdcard/sw_clip.mp4' | Out-Null
}
function Set-TestFree([bool]$enabled) {
    Tap 948 2214; Start-Sleep 1
    TapOn '^Settings$' | Out-Null; Start-Sleep 1
    Swipe 500 1950 500 700 500; Start-Sleep 1
    Sh 'uiautomator dump /sdcard/sw_ui.xml' | Out-Null
    $xml = [xml]((Sh 'cat /sdcard/sw_ui.xml') -join '')
    $node = $xml.SelectSingleNode("//node[contains(@resource-id,'switchTestFree')]")
    if (-not $node) { throw 'Free-user test switch not found' }
    if (($node.checked -eq 'true') -ne $enabled) {
        if ($node.bounds -notmatch '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') { throw 'Switch bounds unavailable' }
        Tap ([int](([int]$Matches[1]+[int]$Matches[3])/2)) ([int](([int]$Matches[2]+[int]$Matches[4])/2))
        Start-Sleep 1
    }
    Swipe 500 1950 500 700 500; Start-Sleep 1
    Sh 'uiautomator dump /sdcard/sw_ui.xml' | Out-Null
    $xml = [xml]((Sh 'cat /sdcard/sw_ui.xml') -join '')
    $node = $xml.SelectSingleNode("//node[contains(@resource-id,'switchTestFree')]")
    if (-not $node -or (($node.checked -eq 'true') -ne $enabled)) { throw 'Free-user switch confirmation failed' }
    Back; Start-Sleep 1
}
function Home { Tap 130 2214; Start-Sleep 1 }
function Open-HomeDoc([string]$title) {
    Home
    $node = Find $title | Select-Object -First 1
    if (-not $node) { Swipe 500 1900 500 900 500; Start-Sleep 1; $node = Find $title | Select-Object -First 1 }
    if (-not $node) { throw "Document not found: $title" }
    Tap $node.cx $node.cy; Start-Sleep 2
}

Sh 'settings put global sysui_demo_allowed 1' | Out-Null
Sh 'am broadcast -a com.android.systemui.demo -e command enter' | Out-Null
Sh 'am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1000' | Out-Null
Sh 'am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false' | Out-Null
Sh 'am broadcast -a com.android.systemui.demo -e command network -e wifi hide -e mobile show -e level 4 -e datatype none' | Out-Null
Sh 'am broadcast -a com.android.systemui.demo -e command notifications -e visible false' | Out-Null
StartApp; Start-Sleep 3
try {
    Set-TestFree $false
    Tap 315 2214; Start-Sleep 1
    TapOn 'Find a tool' | Out-Null; TypeText 'private'; Start-Sleep 1; Back
    TapOn '^Redact$' | Out-Null; Start-Sleep 2
    TapOn 'Offer letter - Priya Nair' | Out-Null; Start-Sleep 2
    Begin-StoryClip 'private' 14
    Start-Sleep 1; Tap 432 2034; Start-Sleep 2; Tap 540 2025; Start-Sleep 3
    Tap 540 2025; Start-Sleep 2; Tap 541 1300
    End-StoryClip
    Back; Start-Sleep 1; TapOn '^Discard$' | Out-Null; Start-Sleep 1; Back; Start-Sleep 1

    Set-TestFree $true
    TapOn '^ScanWhirl Pro$' | Out-Null; Start-Sleep 2
    Begin-StoryClip 'pro' 8
    Start-Sleep 1
    End-StoryClip
    Back; Start-Sleep 1; Home
    Begin-StoryClip 'home' 5
    End-StoryClip

    Open-HomeDoc 'Invoice - Sharma Traders'
    Begin-StoryClip 'invoice' 7
    Start-Sleep 1; Tap 250 800
    End-StoryClip
    Back; Start-Sleep 1; Back; Start-Sleep 1
    Open-HomeDoc 'Physics notes - Chapter 4'
    Begin-StoryClip 'ocr' 7
    Start-Sleep 1; Tap 738 2234
    End-StoryClip
    Back; Start-Sleep 1; Back; Start-Sleep 1; Home
    Swipe 500 600 500 1900 700; Start-Sleep 1

    Begin-StoryClip 'language' 9
    Start-Sleep 1; Tap 950 528; Start-Sleep 1; Tap 540 2034
    End-StoryClip
    Tap 950 528; Start-Sleep 1; Tap 540 1890; Start-Sleep 2
    Tap 315 2214; Start-Sleep 1
    Begin-StoryClip 'tools' 8
    Start-Sleep 1; Swipe 500 1800 500 800 1000
    End-StoryClip
} finally {
    Sh 'am broadcast -a com.android.systemui.demo -e command exit' | Out-Null
    Sh 'settings put global sysui_demo_allowed 0' | Out-Null
}
