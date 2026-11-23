@echo off
setlocal
cd /d "%~dp0"
set PYTHONUTF8=1
if not exist ".venv\Scripts\python.exe" (
  echo Run Setup-Windows.cmd first.
  pause
  exit /b 1
)
".venv\Scripts\python.exe" -m pc_engine serve %*
if errorlevel 1 pause
