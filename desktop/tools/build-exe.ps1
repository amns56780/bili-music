# Build a self-extracting Windows installer (EXE) for the desktop app.
#
# Why not jpackage: `packageExe` / `packageMsi` need the WiX Toolset, and jpackage
# writes through child processes (blocked in some restricted environments).
# IExpress ships with Windows, needs no toolchain, and produces a normal .exe.
#
# Note: the payload is staged FLAT (all jars in the package root) because IExpress
# is unreliable with sub-directory entries in the SED file list.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File desktop\tools\build-exe.ps1
#
# Output: desktop\build\distributions\bilimusic-setup.exe

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$portable = Join-Path $repoRoot 'desktop\build\portable\bilimusic'
$stage = Join-Path $repoRoot 'desktop\build\exe-staging'
$distDir = Join-Path $repoRoot 'desktop\build\distributions'
$outExe = Join-Path $distDir 'bilimusic-setup.exe'
$iconSrc = Join-Path $repoRoot 'desktop\src\main\resources\icons\bilimusic.ico'

Write-Host '[1/6] Portable payload'
if (Test-Path (Join-Path $portable 'lib')) {
    Write-Host '  reusing existing desktop\build\portable\bilimusic'
} else {
    Write-Host '  building it (gradle :desktop:portableDist) - requires JAVA_HOME / GRADLE_USER_HOME if set in your env'
    Push-Location $repoRoot
    try {
        & .\gradlew.bat :desktop:portableDist --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'gradle :desktop:portableDist failed' }
    } finally {
        Pop-Location
    }
}

Write-Host '[2/6] Staging a FLAT package root'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
# lib/ 下的 jar 全部摊平到根目录（IExpress 更可靠），用 -cp "*" 启动
Get-ChildItem (Join-Path $portable 'lib') -File | ForEach-Object { Copy-Item $_.FullName $stage -Force }
$libCount = (Get-ChildItem $stage -File).Count
Write-Host ("  {0} jars staged" -f $libCount)
if (Test-Path $iconSrc) { Copy-Item $iconSrc (Join-Path $stage 'bilimusic.ico') -Force }

Write-Host '[3/6] Writing launcher and installer scripts'
@'
@echo off
cd /d %~dp0
start "bilimusic" javaw -Dfile.encoding=UTF-8 -cp "*" com.bilimusic.desktop.MainKt
'@ | Set-Content (Join-Path $stage 'bilimusic.bat') -Encoding ASCII

@'
@echo off
setlocal
set "TARGET=%LOCALAPPDATA%\Programs\bilimusic"
echo.
echo   Installing bilimusic to:
echo   %TARGET%
echo.
if not exist "%TARGET%" mkdir "%TARGET%"
xcopy /E /Y /I /Q "%~dp0*" "%TARGET%\" >nul
powershell -NoProfile -Command "$ws=New-Object -ComObject WScript.Shell; $lnk=$ws.CreateShortcut([Environment]::GetFolderPath('Desktop')+'\bilimusic.lnk'); $lnk.TargetPath='%TARGET%\bilimusic.bat'; $lnk.WorkingDirectory='%TARGET%'; $lnk.IconLocation='%TARGET%\bilimusic.ico'; $lnk.Description='bilimusic'; $lnk.Save()"
echo   Done. Desktop shortcut "bilimusic" created.
echo   NOTE: VLC must be installed (https://www.videolan.org) for playback.
echo.
start "" "%TARGET%\bilimusic.bat"
'@ | Set-Content (Join-Path $stage 'install.bat') -Encoding ASCII

@'
bilimusic desktop (portable / installer build)

Requirements before running:
  1) A JRE or JDK 17 or newer, with `java` on PATH
  2) VLC installed (https://www.videolan.org) - used as the playback engine

Run:
  - double-click bilimusic.bat
Installed copy (if you used bilimusic-setup.exe):
  - %LOCALAPPDATA%\Programs\bilimusic

Data directory: %USERPROFILE%\.bilimusic
  settings.json   login cookie + preferences  (stored in PLAIN TEXT - keep it private)
  playlists.json  playlists and songs
  downloads\      downloaded audio for offline playback
'@ | Set-Content (Join-Path $stage 'README.txt') -Encoding ASCII

# IExpress SED is read as ANSI: file names must be ASCII
Get-ChildItem $stage -File | Where-Object { $_.Name -match '[^\x20-\x7E]' } | ForEach-Object {
    $ascii = ($_.Name -replace '[^\x20-\x7E]', '_')
    Write-Host ("  renaming {0} -> {1}" -f $_.Name, $ascii)
    Rename-Item $_.FullName $ascii -Force
}

Write-Host '[4/6] Generating IExpress SED'
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$files = Get-ChildItem $stage -File | ForEach-Object { $_.Name }
if ($files -notcontains 'install.bat') { throw 'install.bat missing from package list' }
if ($files -notcontains 'bilimusic.bat') { throw 'bilimusic.bat missing from package list' }

$i = 0
$strings = ($files | ForEach-Object { $line = "FILE$i=`"$_`""; $i++; $line }) -join "`r`n"
$i = 0
$srcList = ($files | ForEach-Object { $line = "%FILE$i%="; $i++; $line }) -join "`r`n"

$sed = @"
[Version]
Class=IEXPRESS
SEDVersion=3
[Options]
PackagePurpose=InstallApp
ShowInstallProgramWindow=1
HideExtractAnimation=0
UseLongFileName=1
InsideCompressed=0
CAB_FixedSize=0
CAB_ResvCodeSigning=0
RebootMode=N
InstallPrompt=
DisplayLicense=
FinishMessage=
TargetName=$outExe
FriendlyName=bilimusic
AppLaunched=install.bat
PostInstallCmd=<None>
AdminQuietInstCmd=
UserQuietInstCmd=
SourceFiles=SourceFiles
[Strings]
$strings
[SourceFiles]
SourceFiles0=$stage\
[SourceFiles0]
$srcList
"@
$sedFile = Join-Path $env:TEMP 'bilimusic.sed'
[System.IO.File]::WriteAllText($sedFile, $sed, [System.Text.Encoding]::GetEncoding(936))
Write-Host ("  {0} files listed (install.bat verified present)" -f $files.Count)
Write-Host ("  SED: {0}" -f $sedFile)

Write-Host '[5/6] Running IExpress (may take a few minutes)'
$proc = Start-Process -FilePath "$env:WINDIR\System32\iexpress.exe" `
    -ArgumentList '/N', '/Q', $sedFile -Wait -PassThru -WindowStyle Hidden
if ($proc.ExitCode -ne 0) { throw "iexpress failed with exit code $($proc.ExitCode)" }

Write-Host '[6/6] Done'
if (Test-Path $outExe) {
    $size = [math]::Round((Get-Item $outExe).Length / 1MB, 2)
    Write-Host ("  {0}  ({1} MB)" -f $outExe, $size)
} else {
    throw "expected output not found: $outExe"
}
