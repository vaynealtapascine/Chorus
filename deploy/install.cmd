@echo off
rem Sets Chorus up (asks for administrator rights). Double-click this file.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
