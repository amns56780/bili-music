# Build a self-extracting Windows installer (EXE) for the desktop app.
#
# Why not jpackage: `packageExe` / `packageMsi` need the WiX Toolset, and jpackage
# also writes through child processes (blocked in some restricted environments).
# IExpress ships with Windows, needs no toolchain, and produces a normal .exe.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File desktop\tools\build-exe.ps1
#
# Output: desktop\build\distributions\bilimusic-setup.exe

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$payload = Join-Path $repoRoot 'desktop\build\portable\bilimusic'
$distDir = Join-Path $repoRoot 'desktop\build\distributions'
$outExe = Join-Path $distDir 'bilimusic-setup.exe'

Write-Host '[1/5] Building portable payload (gradle :desktop:portableDist)'
Push-Location $repoRoot
try {
    & .\gradlew.bat :desktop:portableDist --console=plain
    if ($LASTEXITCODE -ne 0) { throw "gradle :desktop:portableDist failed" }
} finally {
    Pop-Location
}

if (-not (Test-Path (Join-Path $payload 'lib'))) {
    throw "payload not found: $payload"
}

Write-Host '[2/5] Writing launcher and installer scripts'
# Launcher (kept ASCII: it is executed by cmd.exe)
@'
@echo off
cd /d %~dp0
start "bilimusic" javaw -Dfile.encoding=UTF-8 -cp "lib/*" com.bilimusic.desktop.MainKt
'@ | Set-Content (Join-Path $payload 'bilimusic.bat') -Encoding ASCII

# Installer: copy to %LOCALAPPDATA%\Programs\bilimusic, create a desktop shortcut, launch
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
powershell -NoProfile -Command "$ws=New-Object -ComObject WScript.Shell; $lnk=$ws.CreateShortcut([Environment]::GetFolderPath('Desktop')+'\bilimusic.lnk'); $lnk.TargetPath='%TARGET%\bilimusic.bat'; $lnk.WorkingDirectory='%TARGET%'; $lnk.Description='bilimusic desktop'; $lnk.Save()"
echo   Done. Desktop shortcut "bilimusic" created.
echo   NOTE: VLC must be installed (https://www.videolan.org) for playback.
echo.
start "" "%TARGET%\bilimusic.bat"
'@ | Set-Content (Join-Path $payload 'install.bat') -Encoding ASCII

# IExpress SED is read as ANSI: rename any non-ASCII file name first
Get-ChildItem $payload -Recurse -File | Where-Object { $_.Name -match '[^\x00-\x7F]' } | ForEach-Object {
    $ascii = ($_.Name -replace '[^\x20-\x7E]', '_')
    Write-Host ("  renaming {0} -> {1}" -f $_.Name, $ascii)
    Rename-Item $_.FullName $ascii -Force
}

Write-Host '[3/5] Generating IExpress SED'
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$files = Get-ChildItem $payload -Recurse -File | ForEach-Object { $_.FullName.Substring($payload.Length + 1) }
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
SourceFiles0=$payload\
[SourceFiles0]
$srcList
"@
$sedFile = Join-Path $env:TEMP 'bilimusic.sed'
[System.IO.File]::WriteAllText($sedFile, $sed, [System.Text.Encoding]::GetEncoding(936))
Write-Host ("  {0} files packaged" -f $files.Count)

Write-Host '[4/5] Running IExpress (may take a few minutes)'
$proc = Start-Process -FilePath "$env:WINDIR\System32\iexpress.exe" `
    -ArgumentList '/N', '/Q', $sedFile -Wait -PassThru -WindowStyle Hidden
if ($proc.ExitCode -ne 0) { throw "iexpress failed with exit code $($proc.ExitCode)" }

Write-Host '[5/5] Done'
if (Test-Path $outExe) {
    $size = [math]::Round((Get-Item $outExe).Length / 1MB, 2)
    Write-Host ("  {0}  ({1} MB)" -f $outExe, $size)
} else {
    throw "expected output not found: $outExe"
}
