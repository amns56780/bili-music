@echo off
chcp 936 >nul
title bilimusic 安装程序
setlocal enabledelayedexpansion
set "DEF=%LOCALAPPDATA%\Programs\bilimusic"

echo.
echo   ================================================
echo      bilimusic 桌面版 · 安装程序
echo   ================================================
echo.
echo   默认安装位置：
echo     %DEF%
echo.
echo   （不输入直接回车 = 使用默认位置；也可以粘贴别的目录）
set "TARGET="
set /p "TARGET=   安装目录（回车用默认）："
if "%TARGET%"=="" set "TARGET=%DEF%"

rem 去掉可能粘进来的引号，并裁掉首尾空格
set "TARGET=%TARGET:"=%"
for /f "tokens=* delims= " %%a in ("%TARGET%") do set "TARGET=%%a"
:trimtail
if not "%TARGET%"=="" if "%TARGET:~-1%"==" " set "TARGET=%TARGET:~0,-1%" & goto trimtail

if "%TARGET%"=="" set "TARGET=%DEF%"
echo.
echo   安装到：  %TARGET%
if not exist "%TARGET%" mkdir "%TARGET%"

rem 旧版本可能是一堆小 jar；fat jar 版只需要 bilimusic.jar，先清掉旧 jar
del /q "%TARGET%\*.jar" >nul 2>&1

rem 整包复制（会连内置 VLC 的 vlc\ 目录一起复制）
xcopy /E /Y /I /Q "%~dp0*" "%TARGET%\" >nul
if exist "%TARGET%\bm.bat" ren "%TARGET%\bm.bat" bilimusic.bat
if exist "%TARGET%\bm.ico" ren "%TARGET%\bm.ico" bilimusic.ico

rem 找 javaw：JAVA_HOME -> 常见 JDK 17~21 目录 -> PATH
set "JAVAW="
if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
for %%v in (21 20 19 18 17) do (
    if not defined JAVAW if exist "C:\Program Files\Java\jdk-%%v\bin\javaw.exe" set "JAVAW=C:\Program Files\Java\jdk-%%v\bin\javaw.exe"
)
if not defined JAVAW (
    for %%p in ("C:\Program Files\Java" "C:\Program Files\Eclipse Adoptium" "C:\Program Files\Microsoft" "C:\Program Files\Zulu" "C:\Program Files\Amazon Corretto" "C:\Program Files\BellSoft") do (
        if exist "%%~p" for /d %%d in ("%%~p\*") do (
            if not defined JAVAW if exist "%%~d\bin\javaw.exe" set "JAVAW=%%~d\bin\javaw.exe"
        )
    )
)
if not defined JAVAW for /f "delims=" %%i in ('where javaw 2^>nul') do set "JAVAW=%%i"
if not defined JAVAW set "JAVAW=javaw"
echo   使用 Java：%JAVAW%

rem 桌面 + 开始菜单快捷方式。工作目录必须是安装目录：
rem 程序靠工作目录里的 vlc\ 目录找内置播放内核。
powershell -NoProfile -Command "$ws=New-Object -ComObject WScript.Shell; $a='-Dfile.encoding=GBK -Djna.encoding=UTF-8 -jar \"%TARGET%\\bilimusic.jar\"'; foreach($d in @([Environment]::GetFolderPath('Desktop'),[Environment]::GetFolderPath('Programs'))){$l=$ws.CreateShortcut((Join-Path $d 'bilimusic.lnk')); $l.TargetPath='%JAVAW%'; $l.Arguments=$a; $l.WorkingDirectory='%TARGET%'; $l.IconLocation='%TARGET%\bilimusic.ico'; $l.Description='bilimusic'; $l.Save()}"

rem 注册「应用和功能」卸载项（HKCU，不需要管理员权限）
set "UNKEY=HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\bilimusic"
reg add "%UNKEY%" /v DisplayName     /t REG_SZ    /d "bilimusic" /f >nul
reg add "%UNKEY%" /v DisplayVersion  /t REG_SZ    /d "1.2.1" /f >nul
reg add "%UNKEY%" /v Publisher       /t REG_SZ    /d "amns56780" /f >nul
reg add "%UNKEY%" /v DisplayIcon     /t REG_SZ    /d "%TARGET%\bilimusic.ico" /f >nul
reg add "%UNKEY%" /v InstallLocation /t REG_SZ    /d "%TARGET%" /f >nul
reg add "%UNKEY%" /v UninstallString /t REG_SZ    /d "\"%TARGET%\uninstall.bat\"" /f >nul
reg add "%UNKEY%" /v NoModify        /t REG_DWORD /d 1 /f >nul
reg add "%UNKEY%" /v NoRepair        /t REG_DWORD /d 1 /f >nul

echo.
echo   安装完成：
echo     程序目录  %TARGET%
echo     启动方式  桌面 / 开始菜单的 bilimusic，或双击安装目录里的 bilimusic.bat
echo     播放内核  VLC 已内置（vlc 目录），无需另外安装 VLC
echo     歌单数据  %USERPROFILE%\.bilimusic（卸载时可以选择保留）
echo.
choice /c YN /n /m "   现在启动 bilimusic？[Y/N] "
if errorlevel 2 goto :done
start "" "%JAVAW%" -jar "%TARGET%\bilimusic.jar"
:done
echo.
echo   安装程序结束，窗口 3 秒后自动关闭。
timeout /t 3 >nul
