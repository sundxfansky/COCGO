@echo off
rem Starts the Windows ImageMatchTool.
rem Dependencies are installed once into a local .venv next to this file; later launches reuse it
rem and only reinstall when requirements.txt changes.
rem Usage: ImageMatchTool.bat [screenshot.png]   (a screenshot can also be dropped onto this file)
setlocal
cd /d "%~dp0"
set "VENV=%~dp0.venv"
set "STAMP=%VENV%\requirements.installed"

rem Fast path: venv exists and was built from the current requirements.txt.
if exist "%VENV%\Scripts\pythonw.exe" if exist "%STAMP%" (
    fc /b "%~dp0requirements.txt" "%STAMP%" >nul 2>nul && goto :run
)

echo First launch: setting up dependencies in %VENV% ...
if not exist "%VENV%\Scripts\python.exe" (
    where py >nul 2>nul
    if errorlevel 1 (
        python -m venv "%VENV%" || goto :failed
    ) else (
        py -3 -m venv "%VENV%" || goto :failed
    )
)
"%VENV%\Scripts\python.exe" -m pip install --disable-pip-version-check -r "%~dp0requirements.txt" || goto :failed
copy /y "%~dp0requirements.txt" "%STAMP%" >nul

:run
start "" "%VENV%\Scripts\pythonw.exe" "%~dp0image_match_tool.py" %*
exit /b 0

:failed
echo Failed to set up dependencies. Check that Python 3 is installed and the network is reachable.
pause
exit /b 1
