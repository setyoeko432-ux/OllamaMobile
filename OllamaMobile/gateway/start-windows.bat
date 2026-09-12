@echo off
cd /d "%~dp0"
python server.py --workspace "%USERPROFILE%\OllamaWorkspace"
pause
