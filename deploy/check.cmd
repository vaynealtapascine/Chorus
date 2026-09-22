@echo off
rem Shows what Chorus setup would do, and changes nothing.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -Check
pause
