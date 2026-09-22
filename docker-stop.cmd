@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0ops\stop-local.ps1" %*
if errorlevel 1 pause
