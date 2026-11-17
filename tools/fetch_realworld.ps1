# Polite download of real-world test photos (Wikimedia Commons, free licences) for QA. Test data only, never shipped.
# Uses standard thumbnail widths and waits between requests (Wikimedia rate limits).
param([string]$Out = "C:\Users\Cybium\Desktop\ScanWhirl-HQ\qa\realworld", [int]$Width = 1920, [int]$DelaySec = 6)
$ua = @{ 'User-Agent' = 'ScanWhirlTestFetcher/1.0 (offline app QA; low volume)' }
New-Item -ItemType Directory -Force $Out | Out-Null
$files = [ordered]@{}
Get-Content "$env:TEMP\swdevice\books.txt" | ForEach-Object {
    if ($_ -match '^#(\d+) (.+)$' -and (2, 5, 9, 12, 16, 18) -contains [int]$Matches[1]) { $files[("book_{0:D2}.jpg" -f [int]$Matches[1])] = $Matches[2] }
}
$files['id_lux_front.jpg'] = 'https://upload.wikimedia.org/wikipedia/commons/3/32/Luxembourg_Identity_Card_2021.jpg'
$files['id_lux_back.jpg'] = 'https://upload.wikimedia.org/wikipedia/commons/0/0b/Luxembourg_Identity_Card_2021_verso.jpg'
$files['id_fr_front.jpg'] = 'https://upload.wikimedia.org/wikipedia/commons/2/23/French_Identity_card_1988_-_1994.jpg'
$files['id_fr_back.jpg'] = 'https://upload.wikimedia.org/wikipedia/commons/2/2b/French_Identity_card_1988_-_1994_Back.jpg'
$files['dl_gr_front.jpg'] = 'https://upload.wikimedia.org/wikipedia/commons/7/75/2013-2023_GRC_Driving_Licence_Specimen_FRONT.jpg'
$files['dl_gr_back.jpg'] = 'https://upload.wikimedia.org/wikipedia/commons/d/dc/2013-2023_GRC_Driving_Licence_Specimen_BACK.jpg'
foreach ($name in $files.Keys) {
    $dst = Join-Path $Out $name
    if ((Test-Path $dst) -and (Get-Item $dst).Length -gt 20000) { "skip $name (have it)"; continue }
    $u = $files[$name]
    # Large book photos: standard thumbnail; small ID images (< $Width px) come as originals.
    $leaf = Split-Path $u -Leaf
    $thumb = $u -replace '/wikipedia/commons/(\w)/(\w\w)/', '/wikipedia/commons/thumb/$1/$2/'
    $url = if ($name -like 'book_*') { "$thumb/${Width}px-$leaf" } else { $u }
    try { Invoke-WebRequest -UseBasicParsing -Uri $url -Headers $ua -OutFile $dst -TimeoutSec 60; "ok   $name  $((Get-Item $dst).Length) bytes" }
    catch { "FAIL $name : $($_.Exception.Message.Split([Environment]::NewLine)[0])"; Remove-Item $dst -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds $DelaySec
}
($files.Keys | ForEach-Object { "$_  $($files[$_])" }) | Out-File (Join-Path $Out "SOURCES.txt") -Encoding utf8
