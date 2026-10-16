@echo off
setlocal EnableExtensions
cd /d "%~dp0"

where py >nul 2>&1
if %errorlevel%==0 (
  set "PY=py -3"
) else (
  set "PY=python"
)

if not exist "server\.venv\Scripts\python.exe" (
  echo Creating a Python environment...
  %PY% -m venv server\.venv
  if errorlevel 1 goto :fail
)

echo Installing the server. The first time takes a minute.
server\.venv\Scripts\python.exe -m pip install --upgrade pip
server\.venv\Scripts\python.exe -m pip install -r server\requirements.txt
if errorlevel 1 goto :fail

cd server
.venv\Scripts\python.exe -m app.ensure_config
if errorlevel 1 goto :fail
.venv\Scripts\python.exe -m app.main
if errorlevel 1 goto :stopped
exit /b 0

:fail
echo.
echo Setup failed. Install Python 3.11 or newer from https://www.python.org/downloads/
echo Tick "Add python.exe to PATH" in the installer, then run this file again.
pause
exit /b 1

:stopped
echo.
echo Desk stopped. If this was not on purpose, read the message above.
pause
exit /b 1
