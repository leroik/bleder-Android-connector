@echo off
setlocal
cd /d "%~dp0"
set PYTHONUTF8=1
if not exist ".venv\Scripts\python.exe" (
  py -3.12 -m venv .venv
  if errorlevel 1 goto fail
)
".venv\Scripts\python.exe" -c "import struct; assert struct.calcsize('P') == 8"
if errorlevel 1 goto fail
".venv\Scripts\python.exe" -m pip install -r pc_engine\requirements-windows.lock
if errorlevel 1 goto fail
echo Setup complete. Run Start-Scanner.cmd.
if /i not "%~1"=="--ci" pause
exit /b 0
:fail
echo Setup failed. Install 64-bit Python 3.12 with the Windows Python launcher.
if /i not "%~1"=="--ci" pause
exit /b 1
