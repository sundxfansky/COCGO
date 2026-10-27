@echo off
rem Starts the Windows ImageMatchTool; installs the pinned Python dependencies on first run.
rem Usage: ImageMatchTool.bat [screenshot.png]   (a screenshot can also be dropped onto this file)
setlocal
cd /d "%~dp0"
rem Prefer the py launcher; pyw/pythonw run the GUI without a console window.
where py >nul 2>nul
if %errorlevel%==0 (
    set "PY=py -3"
    set "PYW=pyw -3"
) else (
    set "PY=python"
    set "PYW=pythonw"
)
%PY% -c "import numpy, PIL, tkinterdnd2" >nul 2>nul
if not %errorlevel%==0 (
    echo Installing dependencies...
    %PY% -m pip install --user -r requirements.txt || goto :install_failed
)
start "" %PYW% "%~dp0image_match_tool.py" %*
exit /b 0

:install_failed
echo Failed to install dependencies.
pause
exit /b 1
