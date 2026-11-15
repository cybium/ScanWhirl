# Lead pre-merge review of a lane branch against integration (no merge is performed).
# Usage: powershell -File tools\lane-review.ps1 -Branch lane/L1-shell [-Own 'regex of paths the lane owns']
param([Parameter(Mandatory = $true)][string]$Branch, [string]$Own = '')
$wt = @{ 'lane/L1-shell' = 'ScanWhirl-L1'; 'lane/L2-vault' = 'ScanWhirl-L2'; 'lane/L3-protect' = 'ScanWhirl-L3'; 'lane/L4-tools' = 'ScanWhirl-L4'; 'lane/L5-stamp' = 'ScanWhirl-L5' }[$Branch]
"=== $Branch"
git log --oneline "integration..$Branch" | Select-Object -First 15
"--- manifest/build changes"
git diff "integration...$Branch" -- app/src/main/AndroidManifest.xml app/build.gradle.kts | Select-String '^[+-][^+-]' | ForEach-Object Line
"--- removed @Test / added @Ignore"
git diff -U1 "integration...$Branch" -- app/src/test | Select-String '^-\s*@Test|^\+.*@Ignore' | ForEach-Object Line
$files = @(git diff --name-only "integration...$Branch")
if ($Own) {
    "--- files outside ownership ($Own)"
    $files | Where-Object { $_ -notmatch $Own }
}
"--- whole-file rewrites (>100 added and >100 removed lines)"
git diff --numstat "integration...$Branch" | ForEach-Object {
    $a = $_ -split "`t"
    if ($a[0] -ne '-' -and [int]$a[0] -gt 100 -and [int]$a[1] -gt 100) { "  $_" }
}
"--- files with a UTF-8 BOM"
foreach ($f in $files) {
    $bytes = git show "${Branch}:$f" 2>$null | Select-Object -First 1
    if ($bytes -and [int][char]$bytes[0] -eq 0xFEFF) { "  $f" }
}
if ($wt) {
    "--- new English strings without Hindi"
    $root = "C:\Users\Cybium\Desktop\$wt"
    git diff "integration...$Branch" -- 'app/src/main/res/values/*.xml' | Select-String '^\+\s*<(string|plurals) name="([^"]+)"' | ForEach-Object {
        $n = $_.Matches[0].Groups[2].Value
        $hit = Select-String -Path "$root\app\src\main\res\values-hi\*.xml" -Pattern "name=`"$n`"" -Quiet
        if (-not $hit) { "  MISSING hi: $n" }
    }
}
"--- stat"
git diff --stat "integration...$Branch" | Select-Object -Last 1
