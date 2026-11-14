# Copy audit: counts long dashes and long strings in user-visible English strings.
# Usage: powershell -File tools\copy_audit.ps1 [-List]
param([switch]$List, [int]$Max = 90)
$dash = [char]0x2014
$files = Get-ChildItem "$PSScriptRoot\..\app\src\main\res\values" -Filter 'strings*.xml'
$all = foreach ($f in $files) {
    [xml]$x = Get-Content $f.FullName -Encoding UTF8
    foreach ($s in $x.resources.string) { [pscustomobject]@{ file = $f.Name; name = $s.name; text = $s.InnerText } }
}
"total strings: $($all.Count)"
"with long dash: $(@($all | Where-Object { $_.text.Contains($dash) }).Count)"
"longer than $Max chars: $(@($all | Where-Object { $_.text.Length -gt $Max }).Count)"
"--- per file (dash / long)"
$all | Group-Object file | ForEach-Object { "{0,-26} {1,4} {2,4}" -f $_.Name, @($_.Group | Where-Object { $_.text.Contains($dash) }).Count, @($_.Group | Where-Object { $_.text.Length -gt $Max }).Count }
if ($List) {
    "--- dash or long"
    $all | Where-Object { $_.text.Contains($dash) -or $_.text.Length -gt $Max } | ForEach-Object { "[$($_.file)] $($_.name) ($($_.text.Length)): $($_.text)" }
}
