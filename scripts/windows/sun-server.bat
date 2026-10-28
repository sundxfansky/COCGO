@echo off
rem Builds (if needed) and runs the cloud config service on Windows without Docker.
rem All arguments are passed to sun-server.ps1, e.g. sun-server.bat -Port 8080 -Rebuild
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sun-server.ps1" %*
if errorlevel 1 pause
exit /b %errorlevel%
