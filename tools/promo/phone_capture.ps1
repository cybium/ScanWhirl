# Dot-source from the repository root. Only the fictional demo package is used.
. "$PSScriptRoot\..\phone.ps1"
$script:pkg = 'com.scanwhirl.app.demo'
$script:promo = 'C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\promo'
New-Item -ItemType Directory -Force "$script:promo\raw" | Out-Null

function Begin-PromoClip([string]$name, [int]$seconds = 30) {
    $script:clipName = $name
    $script:clipProcess = Start-Process -PassThru -WindowStyle Hidden -FilePath $script:adb -ArgumentList "-s $script:ser shell screenrecord --time-limit $seconds --bit-rate 12000000 /sdcard/promo_$name.mp4"
    Start-Sleep -Milliseconds 700
}
function End-PromoClip {
    $script:clipProcess.WaitForExit()
    A pull "/sdcard/promo_$script:clipName.mp4" "$script:promo\raw\$script:clipName.mp4"
    Sh "rm /sdcard/promo_$script:clipName.mp4" | Out-Null
}
function Restore-PromoPhone {
    Sh 'am broadcast -a com.android.systemui.demo -e command exit' | Out-Null
    Sh 'settings put global sysui_demo_allowed 0' | Out-Null
    # Restore the demo app's Free-user switch through its UI before this call.
    Sh 'input keyevent KEYCODE_HOME' | Out-Null
}
