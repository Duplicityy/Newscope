@echo off
setlocal
cd /d "%~dp0"
if not exist "%LOCALAPPDATA%\Kronos-Windows" mkdir "%LOCALAPPDATA%\Kronos-Windows"
start "" "%~dp0runtime\bin\javaw.exe" -Xmx1g "-Duser.home=%LOCALAPPDATA%\Kronos-Windows" -Dsun.java2d.d3d=false -cp "%~dp0app\newscape-launcher.jar;%~dp0app\windows-support.jar;%~dp0app\client.jar" WindowsFreshCacheLauncher
endlocal
