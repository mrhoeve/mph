@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-plugin-local.ps1" %*
exit /b %ERRORLEVEL%
