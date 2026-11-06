@echo off
setlocal
set "TIHO_PROFILE=%APPDATA%\Tiho\profile"
if not exist "%TIHO_PROFILE%" mkdir "%TIHO_PROFILE%"
start "" "%~dp0firefox\firefox.exe" -profile "%TIHO_PROFILE%" -private-window %*
