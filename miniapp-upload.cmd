@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0ops\upload-miniapp.ps1" %*
set "exitCode=%ERRORLEVEL%"
if not "%exitCode%"=="0" pause
exit /b %exitCode%
