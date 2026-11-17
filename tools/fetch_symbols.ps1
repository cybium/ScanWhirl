# Downloads official Material Symbols Rounded (Apache 2.0) SVGs and converts them to Android vector drawables.
# Usage: powershell -File tools\fetch_symbols.ps1 -Out C:\Users\Cybium\Desktop\ScanWhirl-HQ\assets\symbols -Names a,b,c
# Name forms: "name" (outlined), "name:fill" (filled variant), "target=source" (file ic_sym_target from symbol source).
param([Parameter(Mandatory = $true)][string]$Out, [Parameter(Mandatory = $true)][string[]]$Names)
$Names = @($Names | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
New-Item -ItemType Directory -Force $Out | Out-Null
$ok = 0; $bad = @()
foreach ($raw in $Names) {
    $target = $raw; $source = $raw
    if ($raw -match '=') { $target, $source = $raw.Split('=', 2) }
    $fill = $source.EndsWith(':fill'); $source = $source -replace ':fill$', ''
    $target = $target -replace ':fill$', ''
    $style = if ($fill) { 'fill1' } else { 'default' }
    $url = "https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/$source/$style/24px.svg"
    try {
        $svg = (Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 20).Content
        if ($svg -is [byte[]]) { $svg = [Text.Encoding]::UTF8.GetString($svg) }
        $paths = [regex]::Matches($svg, '<path[^>]*\sd="([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
        if (-not $paths) { throw "no path" }
        $vb = [regex]::Match($svg, 'viewBox="([^"]+)"').Groups[1].Value.Split(' ')
        $minY = [double]$vb[1]; $w = $vb[2]; $h = $vb[3]
        $sb = New-Object Text.StringBuilder
        [void]$sb.AppendLine('<?xml version="1.0" encoding="utf-8"?>')
        [void]$sb.AppendLine("<!-- Material Symbols Rounded '$source'$(if($fill){' (filled)'}), Apache License 2.0, Google. -->")
        [void]$sb.AppendLine('<vector xmlns:android="http://schemas.android.com/apk/res/android"')
        [void]$sb.AppendLine("    android:width=""24dp"" android:height=""24dp""")
        [void]$sb.AppendLine("    android:viewportWidth=""$w"" android:viewportHeight=""$h"">")
        [void]$sb.AppendLine("    <group android:translateY=""$(-$minY)"">")
        foreach ($p in $paths) { [void]$sb.AppendLine("        <path android:fillColor=""@android:color/white"" android:pathData=""$p"" />") }
        [void]$sb.AppendLine('    </group>')
        [void]$sb.AppendLine('</vector>')
        [IO.File]::WriteAllText((Join-Path $Out "ic_sym_$target.xml"), $sb.ToString(), (New-Object Text.UTF8Encoding($false)))
        $ok++
    } catch { $bad += "$raw ($($_.Exception.Message))" }
}
"converted: $ok"
if ($bad) { "FAILED: " + ($bad -join '; ') }
