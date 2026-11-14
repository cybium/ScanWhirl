# Renders every src/*.html to ../mockups/*.png at phone resolution (412x915 dp at 2.625x = 1081x2402 px).
param([string[]]$Only)
$edge = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
$src = $PSScriptRoot
$out = Join-Path (Split-Path $src) "mockups"
New-Item -ItemType Directory -Force $out | Out-Null
Get-ChildItem $src -Filter *.html | Where-Object { -not $Only -or $Only -contains $_.BaseName } | ForEach-Object {
    $png = Join-Path $out ($_.BaseName + ".png")
    $url = "file:///" + ($_.FullName -replace '\\', '/')
    & $edge --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=2.625 `
        --window-size=412,915 --virtual-time-budget=8000 --screenshot="$png" $url 2>$null | Out-Null
    "{0} -> {1}" -f $_.Name, (Test-Path $png)
}
