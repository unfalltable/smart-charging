@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0ops\demo-charge.ps1" %*
if errorlevel 1 pause
