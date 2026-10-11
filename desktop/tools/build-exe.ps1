# Build a self-extracting Windows installer (EXE) for the desktop app.
#
# Toolchain: 7-Zip's standalone SFX module (downloaded into .tools/, never installed).
# Why not IExpress: IExpress silently drops files when the SED file list contains
# long file names or sub-directories - the produced installer then fails with
# "创建进程 ...\IXP000.TMP\install.bat 时出错" because nothing was extracted.
# 7-Zip SFX is reliable and, unlike IExpress, can be verified from the command line.
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

# 7-Zip standalone tools (kept outside the repo)
$toolsDir = Join-Path (Split-Path $repoRoot -Parent) '.tools\7zip-extracted'
$sevenZip = Join-Path $toolsDir '7z.exe'
$sfxModule = Join-Path $toolsDir '7z.sfx'
if (-not (Test-Path $sevenZip) -or -not (Test-Path $sfxModule)) {
    throw "7-Zip standalone tools not found at $toolsDir. See desktop/README.md (build-exe section)."
}

Write-Host '[1/5] Portable payload'
# delete any previous payload first - never ship a stale jar
Remove-Item (Join-Path $repoRoot 'desktop\build\portable') -Recurse -Force -ErrorAction SilentlyContinue
if (Test-Path (Join-Path $portable 'lib')) {
    Write-Host '  reusing desktop\build\portable\bilimusic'
} else {
    Push-Location $repoRoot
    try {
        & .\gradlew.bat :desktop:portableDist --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'gradle :desktop:portableDist failed' }
    } finally {
        Pop-Location
    }
}

Write-Host '[2/5] Staging a flat package root with 8.3-safe names'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
# jar 统一改成 j001.jar 这种短名；启动脚本用 -cp "*" 通配，所以文件名无所谓。
$n = 1
Get-ChildItem (Join-Path $portable 'lib') -File | Sort-Object Name | ForEach-Object {
    $ext = if ($_.Extension) { $_.Extension } else { '.bin' }
    Copy-Item $_.FullName (Join-Path $stage ('j{0:d3}{1}' -f $n, $ext)) -Force
    $n++
}
if (Test-Path $iconSrc) { Copy-Item $iconSrc (Join-Path $stage 'bm.ico') -Force }
Write-Host ("  {0} payload files staged (all named jNNN.*)" -f ($n - 1))
$stagedBytes = (Get-ChildItem $stage -File | Measure-Object Length -Sum).Sum
Write-Host ("  {0} MB" -f [math]::Round($stagedBytes / 1MB, 1))

Write-Host '[3/5] Writing launcher / installer / readme'
# 启动脚本（调试用；正常使用走快捷方式，直接调 javaw，不会出现控制台窗口）
@'
@echo off
cd /d %~dp0
start "bilimusic" javaw -Dfile.encoding=UTF-8 -cp "*" com.bilimusic.desktop.MainKt
'@ | Set-Content (Join-Path $stage 'bm.bat') -Encoding ASCII

# 安装程序：可自定义目录、界面简洁、完成后询问是否启动；快捷方式直接指向 javaw（无黑窗）
$installBat = @'
@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title bilimusic 安装程序
set "DEF=%LOCALAPPDATA%\Programs\bilimusic"
echo.
echo   ================================================
echo      bilimusic 桌面版 · 安装程序
echo   ================================================
echo.
echo   默认安装到：
echo     %DEF%
echo.
set "TARGET="
set /p "TARGET=   直接回车用默认目录，或粘贴自定义路径后回车："
if "%TARGET%"=="" set "TARGET=%DEF%"
set "TARGET=%TARGET:"=%"
echo.
echo   正在安装到  %TARGET%
if not exist "%TARGET%" mkdir "%TARGET%"
xcopy /E /Y /I /Q "%~dp0*" "%TARGET%\" >nul
if exist "%TARGET%\bm.bat" ren "%TARGET%\bm.bat" bilimusic.bat
if exist "%TARGET%\bm.ico" ren "%TARGET%\bm.ico" bilimusic.ico

rem 找到 javaw，让快捷方式直接指向它（避免弹出黑色控制台窗口）
set "JAVAW=javaw"
for /f "delims=" %%i in ('where javaw 2^>nul') do set "JAVAW=%%i"
powershell -NoProfile -Command "$ws=New-Object -ComObject WScript.Shell; $lnk=$ws.CreateShortcut([Environment]::GetFolderPath('Desktop')+'\bilimusic.lnk'); $lnk.TargetPath='%JAVAW%'; $lnk.Arguments='-Dfile.encoding=UTF-8 -cp \"%TARGET%\*\" com.bilimusic.desktop.MainKt'; $lnk.WorkingDirectory='%TARGET%'; $lnk.IconLocation='%TARGET%\bilimusic.ico'; $lnk.Description='bilimusic'; $lnk.Save()"

echo.
echo   安装完成 ✓  桌面已创建快捷方式「bilimusic」
echo   提示：播放需要系统已安装 VLC（https://www.videolan.org）
echo.
choice /c YN /n /m "   现在启动 bilimusic? [Y/N] "
if errorlevel 2 goto :done
start "" "%JAVAW%" -Dfile.encoding=UTF-8 -cp "%TARGET%\*" com.bilimusic.desktop.MainKt
:done
echo.
echo   窗口可以关闭了。
timeout /t 3 >nul
'@
[System.IO.File]::WriteAllText((Join-Path $stage 'install.bat'), $installBat, (New-Object System.Text.UTF8Encoding($false)))

@'
bilimusic desktop

Requirements before running:
  1) A JRE or JDK 17 or newer, with java on PATH
  2) VLC installed (https://www.videolan.org) - used as the playback engine

Run: double-click bilimusic.bat
Installed copy (if you used bilimusic-setup.exe): %LOCALAPPDATA%\Programs\bilimusic

Data directory: %USERPROFILE%\.bilimusic
  settings.json   login cookie + preferences (PLAIN TEXT - keep it private)
  playlists.json  playlists and songs
  downloads\      downloaded audio for offline playback
'@ | Set-Content (Join-Path $stage 'readme.txt') -Encoding ASCII

if (-not (Test-Path (Join-Path $stage 'install.bat'))) { throw 'install.bat missing' }

Write-Host '[4/5] Packing with 7-Zip and attaching the SFX module'
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$archive = Join-Path $repoRoot 'desktop\build\tmp\bilimusic-payload.7z'
$config = Join-Path $repoRoot 'desktop\build\tmp\bilimusic-sfx-config.txt'
New-Item -ItemType Directory -Force -Path (Split-Path $archive) | Out-Null
Remove-Item $archive, $config -Force -ErrorAction SilentlyContinue

# 7z 正常也会往 stderr 写进度，Stop 模式下会被误判为致命错误，这里临时放开
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & $sevenZip a -t7z -mx=5 $archive "$stage\*" | Out-Null
} finally {
    $ErrorActionPreference = $prevEap
}
if (-not (Test-Path $archive)) { throw '7z archive creation failed' }
Write-Host ("  archive {0} MB" -f [math]::Round((Get-Item $archive).Length / 1MB, 1))

# 7z.sfx 的配置必须是 UTF-8 无 BOM
$sfxConfig = @"
;!@Install@!UTF-8!
Title="bilimusic"
RunProgram="install.bat"
GUIMode="2"
;!@InstallEnd@!
"@
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

Write-Host '[5/5] Done'
if (-not (Test-Path $outExe)) { throw "expected output not found: $outExe" }
Write-Host ("  {0}  ({1} MB)" -f $outExe, [math]::Round((Get-Item $outExe).Length / 1MB, 2))
Remove-Item $archive -Force -ErrorAction SilentlyContinue
