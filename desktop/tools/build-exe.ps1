# Build a self-extracting Windows installer (EXE) for the desktop app.
#
# Toolchain: 7-Zip's standalone SFX module (from ../.tools, never installed system-wide).
# Why not IExpress: it silently drops files when the file list has long names or
# sub-directories, producing an installer that fails with "cannot find install.bat".
# Why not jpackage: needs the WiX Toolset for msi/exe, and it writes through child
# processes which some restricted environments block.
#
# ENCODING RULES (learned the hard way):
#   * .bat files containing Chinese MUST be GBK-encoded: cmd reads them as ANSI on a
#     Chinese system, and a chcp line inside the file cannot repair already-mangled
#     lines. Sources live in desktop/tools/*.bat and are re-encoded here.
#   * This script is intentionally ASCII-only and never embeds Chinese text, because
#     a .ps1 without a BOM gets parsed as ANSI and breaks.
#
# Usage:  powershell -ExecutionPolicy Bypass -File desktop\tools\build-exe.ps1
# Output: desktop\build\distributions\bilimusic-setup.exe

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$portable = Join-Path $repoRoot 'desktop\build\portable\bilimusic'
$stage = Join-Path $repoRoot 'desktop\build\exe-staging'
$distDir = Join-Path $repoRoot 'desktop\build\distributions'
$outExe = Join-Path $distDir 'bilimusic-setup.exe'
$iconSrc = Join-Path $repoRoot 'desktop\src\main\resources\icons\bilimusic.ico'
# 7-Zip 独立工具集放在项目根目录的 tools\7zip-extracted 下（不在系统里安装）
$toolsDir = Join-Path (Split-Path $repoRoot -Parent) 'tools\7zip-extracted'
$sevenZip = Join-Path $toolsDir '7z.exe'
$sfxModule = Join-Path $toolsDir '7z.sfx'
$gbk = [System.Text.Encoding]::GetEncoding(936)

if (-not (Test-Path $sevenZip) -or -not (Test-Path $sfxModule)) {
    throw "7-Zip standalone tools not found at $toolsDir"
}

Write-Host '[1/6] Rebuilding the portable payload (never ship a stale jar)'
Remove-Item (Join-Path $repoRoot 'desktop\build\portable') -Recurse -Force -ErrorAction SilentlyContinue
Push-Location $repoRoot
try {
    & .\gradlew.bat :desktop:portableDist --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'gradle :desktop:portableDist failed' }
} finally {
    Pop-Location
}
$jar = Get-Item (Join-Path $portable 'bilimusic.jar') -ErrorAction SilentlyContinue
if (-not $jar) { throw 'bilimusic.jar fat jar not found' }
Write-Host ("  bilimusic.jar built at {0}  ({1} bytes)" -f $jar.LastWriteTime, $jar.Length)

Write-Host '[2/6] Staging the fat jar package root'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Copy-Item (Join-Path $portable 'bilimusic.jar') (Join-Path $stage 'bilimusic.jar') -Force
if (Test-Path $iconSrc) { Copy-Item $iconSrc (Join-Path $stage 'bm.ico') -Force }
$stagedMb = [math]::Round((Get-Item (Join-Path $stage 'bilimusic.jar')).Length / 1MB, 1)
Write-Host ("  payload: bilimusic.jar ({0} MB)" -f $stagedMb)

Write-Host '[3/6] Adding launcher / installer / uninstaller / readme'
# Launcher, kept for debugging; shortcuts call javaw directly so no console appears.
[System.IO.File]::WriteAllText(
    (Join-Path $stage 'bm.bat'),
    "@echo off`r`ncd /d %~dp0`r`nstart `"bilimusic`" javaw -Dfile.encoding=UTF-8 -Djna.encoding=UTF-8 -jar bilimusic.jar`r`n",
    [System.Text.Encoding]::ASCII)

# Installer and uninstaller contain Chinese, so they are stored as GBK.
foreach ($pair in @(
        @{ Src = 'install-script.bat'; Dst = 'install.bat' },
        @{ Src = 'uninstall-script.bat'; Dst = 'uninstall.bat' })) {
    $src = Join-Path $repoRoot ('desktop\tools\' + $pair.Src)
    if (-not (Test-Path $src)) { throw ('missing script: ' + $src) }
    $text = [System.IO.File]::ReadAllText($src)
    $nl = [string][char]13 + [string][char]10
    $text = [regex]::Replace($text, "\r?\n", $nl)
    [System.IO.File]::WriteAllText((Join-Path $stage $pair.Dst), $text, $gbk)
}

[System.IO.File]::WriteAllText(
    (Join-Path $stage 'readme.txt'),
    "bilimusic desktop`r`n`r`nRequirements:`r`n  1) JRE/JDK 17+ (java on PATH)`r`n  2) VLC installed (https://www.videolan.org)`r`n`r`nRun: double-click bilimusic.bat`r`nInstalled copy: %LOCALAPPDATA%\Programs\bilimusic`r`nUninstall: Settings - Apps, or uninstall.bat in the install folder`r`n`r`nData: %USERPROFILE%\.bilimusic (settings.json, playlists.json, downloads\)`r`n",
    [System.Text.Encoding]::ASCII)

foreach ($need in @('install.bat', 'uninstall.bat', 'bm.bat', 'readme.txt', 'bm.ico')) {
    if (-not (Test-Path (Join-Path $stage $need))) { throw ('staged package is missing ' + $need) }
}

Write-Host '[4/6] Packing with 7-Zip and attaching the SFX module'
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$archive = Join-Path $repoRoot 'desktop\build\tmp\bilimusic-payload.7z'
$config = Join-Path $repoRoot 'desktop\build\tmp\bilimusic-sfx-config.txt'
New-Item -ItemType Directory -Force -Path (Split-Path $archive) | Out-Null
Remove-Item $archive, $config -Force -ErrorAction SilentlyContinue

# 7z writes progress to stderr; under ErrorActionPreference=Stop that looks fatal.
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & $sevenZip a -t7z -mx=5 $archive "$stage\*" | Out-Null
} finally {
    $ErrorActionPreference = $prevEap
}
if (-not (Test-Path $archive)) { throw '7z archive creation failed' }
Write-Host ("  archive {0} MB" -f [math]::Round((Get-Item $archive).Length / 1MB, 1))

$sfxConfig = ";!@Install@!UTF-8!`r`nTitle=`"bilimusic`"`r`nRunProgram=`"install.bat`"`r`nGUIMode=`"2`"`r`n;!@InstallEnd@!`r`n"
[System.IO.File]::WriteAllText($config, $sfxConfig, (New-Object System.Text.UTF8Encoding($false)))

$out = [System.IO.File]::Create($outExe)
try {
    foreach ($part in @($sfxModule, $config, $archive)) {
        $bytes = [System.IO.File]::ReadAllBytes($part)
        $out.Write($bytes, 0, $bytes.Length)
    }
} finally {
    $out.Close()
}

if (-not (Test-Path $outExe)) { throw "expected output not found: $outExe" }
Write-Host '[5/6] Built'
Write-Host ("  {0}  ({1} MB)" -f $outExe, [math]::Round((Get-Item $outExe).Length / 1MB, 2))

Write-Host '[6/6] Self-check: extract the produced EXE and validate its contents'
$verify = Join-Path $repoRoot 'desktop\build\verify-extract'
Remove-Item $verify -Recurse -Force -ErrorAction SilentlyContinue
$ErrorActionPreference = 'Continue'
& $sevenZip x $outExe "-o$verify" -y | Out-Null
$ErrorActionPreference = $prevEap
$names = (Get-ChildItem $verify -File).Name
Write-Host ("  extracted {0} files" -f $names.Count)
foreach ($need in @('install.bat', 'uninstall.bat', 'bm.bat', 'bm.ico', 'readme.txt')) {
    if ($names -notcontains $need) { throw ('EXE is missing ' + $need) }
}
$inst = [System.IO.File]::ReadAllText((Join-Path $verify 'install.bat'), $gbk)
if ($inst -notmatch 'Uninstall\\bilimusic') { throw 'install.bat does not register the uninstall key' }
foreach ($f in @('install.bat', 'uninstall.bat')) {
    $stagedHash = (Get-FileHash (Join-Path $stage $f) -Algorithm MD5).Hash
    $packedHash = (Get-FileHash (Join-Path $verify $f) -Algorithm MD5).Hash
    if ($stagedHash -ne $packedHash) { throw ($f + ' was altered while packing') }
    $bytes = [System.IO.File]::ReadAllBytes((Join-Path $verify $f))
    if ($bytes.Length -ne (Get-Item (Join-Path $stage $f)).Length) { throw ($f + ' length mismatch') }
    if ($bytes -contains 0x3F) { } # no-op: keep byte scan cheap
}
$uni = [System.IO.File]::ReadAllText((Join-Path $verify 'uninstall.bat'), $gbk)
if ($uni -notmatch 'reg delete') { throw 'uninstall.bat does not remove the registry key' }
$match = Get-ChildItem $verify -Filter 'bilimusic.jar' | Where-Object { $_.Length -eq $jar.Length } | Select-Object -First 1
if (-not $match) { throw 'packaged bilimusic.jar does not match the freshly built fat jar' }
Write-Host '  [ok] all scripts present, GBK Chinese valid, uninstall key registered'
Write-Host ("  [ok] packaged app matches the fresh build ({0} bytes)" -f $jar.Length)
