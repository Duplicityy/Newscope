@echo off
setlocal
set "CLIENT=%LOCALAPPDATA%\NewscapeLauncher\client"
if not "%~1"=="" set "CLIENT=%~f1"
if not exist "%CLIENT%\runtime\bin\javaw.exe" goto missing
if not exist "%CLIENT%\app\newscape-launcher.jar" goto missing
if not exist "%CLIENT%\app\windows-support.jar" goto missing
if not exist "%~dp0client-expedition.jar" goto missing
pushd "%CLIENT%" || exit /b 1
start "" "%CLIENT%\runtime\bin\javaw.exe" -Xmx1g "-Duser.home=%LOCALAPPDATA%\Kronos-Windows" -Dsun.java2d.d3d=false -cp "app/newscape-launcher.jar;app/windows-support.jar;%~dp0client-expedition.jar" WindowsFreshCacheLauncher
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%
:missing
echo A Newscape Windows Launcher installation is required. Run the normal launcher once to install the game files.
echo For a custom installation, pass its client directory to this script.
pause
exit /b 1
