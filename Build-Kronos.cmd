@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\gradle.ps1" server :kronos-server:installDist :kronos-update-server:installDist
if errorlevel 1 goto failed
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\gradle.ps1" client :runelite-client:shadowJar
if errorlevel 1 goto failed
echo Build complete.
exit /b 0
:failed
echo Build failed. Read the error above.
pause
exit /b 1
