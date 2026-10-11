@echo off
chcp 65001 >nul
title bilimusic 卸载程序
setlocal enabledelayedexpansion

echo.
echo   ================================================
echo      bilimusic 桌面版 · 卸载
echo   ================================================
echo.

rem 默认安装位置；如果你安装时选了别处，可以直接把这个 bat 放进那个目录再运行
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

rem 如果安装时注册过卸载信息（新版安装程序会注册），一并清掉
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\bilimusic" /f >nul 2>&1
echo   · 已清理注册表项（如果有）

echo.
echo   卸载完成。窗口关闭后会自动删除安装目录。
start "" cmd /c "timeout /t 2 >nul & rmdir /s /q \"%TARGET%\""
exit
