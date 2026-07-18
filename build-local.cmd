@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-local.ps1" %*
exit /b %ERRORLEVEL%
