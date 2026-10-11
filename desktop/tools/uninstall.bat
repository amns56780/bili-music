@echo off
chcp 936 >nul
title bilimusic ж�س���
setlocal enabledelayedexpansion

echo.
echo   ================================================
echo      bilimusic ����� �� ж��
echo   ================================================
echo.

rem Ĭ�ϰ�װλ�ã�����㰲װʱѡ�˱𴦣�����ֱ�Ӱ���� bat �Ž��Ǹ�Ŀ¼������
set "TARGET=%LOCALAPPDATA%\Programs\bilimusic"
if not exist "%TARGET%\bilimusic.bat" (
    if exist "%~dp0bilimusic.bat" (
        set "TARGET=%~dp0"
    ) else (
        echo   û��Ĭ��λ���ҵ� bilimusic��
        echo     %LOCALAPPDATA%\Programs\bilimusic
        echo.
        set /p "TARGET=   ��ճ����İ�װĿ¼���� bilimusic.bat����س���"
    )
)
set "TARGET=%TARGET:"=%"
if not exist "%TARGET%\bilimusic.bat" (
    echo   ���Ŀ¼��û�� bilimusic����ȡ����
    pause
    exit /b 1
)

echo   ��װĿ¼��%TARGET%
echo.
choice /c YN /n /m "   �Ƿ�ͬʱɾ���赥���¼��Ϣ��%USERPROFILE%\.bilimusic��? [Y/N] "
if errorlevel 2 goto keepdata
rmdir /s /q "%USERPROFILE%\.bilimusic"
echo   �� ��ɾ���赥���¼��Ϣ
goto afterdata
:keepdata
echo   �� �ѱ��� %USERPROFILE%\.bilimusic���赥���¼��Ϣ��
:afterdata

rem �����뿪ʼ�˵���ݷ�ʽ����������ڱ𴦣��� PowerShell ȡ��ʵ·����
powershell -NoProfile -Command "Remove-Item (Join-Path ([Environment]::GetFolderPath('Desktop')) 'bilimusic.lnk') -Force -ErrorAction SilentlyContinue; Remove-Item (Join-Path ([Environment]::GetFolderPath('Programs')) 'bilimusic.lnk') -Force -ErrorAction SilentlyContinue"
echo   �� ��ɾ�������뿪ʼ�˵���ݷ�ʽ

rem �����װʱע���ж����Ϣ���°氲װ�����ע�ᣩ��һ�����
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\bilimusic" /f >nul 2>&1
echo   �� ������ע��������У�

echo.
echo   ж����ɡ����ڹرպ���Զ�ɾ����װĿ¼��
start "" cmd /c "timeout /t 2 >nul & rmdir /s /q \"%TARGET%\""
exit
