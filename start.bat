@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "DESK_PY="
where py >nul 2>&1
if not errorlevel 1 (
  py -3 -c "import sys; sys.exit(0 if sys.version_info >= (3,11) else 1)" >nul 2>&1
  if not errorlevel 1 set "DESK_PY=py -3"
)
if defined DESK_PY goto :python_ready
where python >nul 2>&1
if not errorlevel 1 (
  python -c "import sys; sys.exit(0 if sys.version_info >= (3,11) else 1)" >nul 2>&1
  if not errorlevel 1 set "DESK_PY=python"
)
if not defined DESK_PY goto :python_missing

:python_ready
if not exist "server\.venv\Scripts\python.exe" (
  echo Creating a Python environment...
  %DESK_PY% -m venv server\.venv
  if errorlevel 1 goto :fail
)

server\.venv\Scripts\python.exe -c "import sys; sys.exit(0 if sys.version_info >= (3,11) else 1)" >nul 2>&1
if errorlevel 1 goto :old_venv

echo Installing the server. The first time takes a minute.
server\.venv\Scripts\python.exe -m pip install --upgrade pip
if errorlevel 1 goto :fail
server\.venv\Scripts\python.exe -m pip install -r server\requirements.txt
if errorlevel 1 goto :fail

cd server
.venv\Scripts\python.exe -m app.ensure_config
if errorlevel 1 goto :fail
if /I "%~1"=="--install-coder" (
  .venv\Scripts\python.exe -m app.setup_coder
) else (
  .venv\Scripts\python.exe -m app.setup_coder --if-needed
)
if errorlevel 1 echo Coding-model setup is incomplete. Starting the gateway with its existing configuration; restart to retry.
.venv\Scripts\python.exe -m app.main
if errorlevel 1 goto :stopped
exit /b 0

:python_missing
echo Python 3.11 or newer was not found through either py or python.
echo Install it from https://www.python.org/downloads/ and enable Add python.exe to PATH.
pause
exit /b 1

:old_venv
echo The existing server\.venv is broken or uses Python older than 3.11.
echo Rename server\.venv to server\.venv-old, then run start.bat again to rebuild it.
pause
exit /b 1

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

