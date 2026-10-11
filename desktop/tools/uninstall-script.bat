@echo off
chcp 936 >nul
title bilimusic 卸载程序
setlocal enabledelayedexpansion

echo.
echo   ================================================
echo      bilimusic 桌面版 · 卸载程序
echo   ================================================
echo.

rem 默认安装位置；如果你安装时选了别处，可以把这个 bat 放进那个目录再运行
set "TARGET=%LOCALAPPDATA%\Programs\bilimusic"
if not exist "%TARGET%\bilimusic.bat" (
    if exist "%~dp0bilimusic.bat" (
        set "TARGET=%~dp0"
    ) else (
        echo   没在默认位置找到 bilimusic：
        echo     %LOCALAPPDATA%\Programs\bilimusic
        echo.
        set /p "TARGET=   请粘贴你的安装目录（含 bilimusic.bat）后回车："
    )
)
set "TARGET=%TARGET:"=%"
if not exist "%TARGET%\bilimusic.bat" (
    echo   这个目录里没有 bilimusic，已取消。
    pause
    exit /b 1
)

echo   安装目录：%TARGET%
echo.

rem 先结束正在运行的 bilimusic 进程，否则文件被占用删不掉。
rem 只结束命令行里含 com.bilimusic.desktop.MainKt 的进程，
rem Gradle 守护进程等其它 Java 进程一律不动。
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='javaw.exe'\" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*com.bilimusic.desktop.MainKt*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
echo   · 已关闭正在运行的 bilimusic

choice /c YN /n /m "   是否同时删除歌单与登录信息（%USERPROFILE%\.bilimusic）? [Y/N] "
if errorlevel 2 goto keepdata
rmdir /s /q "%USERPROFILE%\.bilimusic"
echo   · 已删除歌单与登录信息
goto afterdata
:keepdata
echo   · 已保留 %USERPROFILE%\.bilimusic（歌单与登录信息）
:afterdata

rem 桌面与开始菜单快捷方式（桌面可能在别处，用 PowerShell 取真实路径）
powershell -NoProfile -Command "Remove-Item (Join-Path ([Environment]::GetFolderPath('Desktop')) 'bilimusic.lnk') -Force -ErrorAction SilentlyContinue; Remove-Item (Join-Path ([Environment]::GetFolderPath('Programs')) 'bilimusic.lnk') -Force -ErrorAction SilentlyContinue"
echo   · 已删除桌面与开始菜单快捷方式

rem 清掉「应用和功能」里的注册表项
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\bilimusic" /f >nul 2>&1
echo   · 已从「应用和功能」注销

echo.
echo   卸载完成，窗口关闭后会自动删除安装目录。
rem 用 ping 做延迟：timeout 在没有控制台的环境下会报错，导致后面的删除不执行
start "" cmd /c "ping -n 3 127.0.0.1 >nul & rd /s /q \"%TARGET%\""
exit
