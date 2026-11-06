@echo off
setlocal
set "TIHO_PROFILE=%APPDATA%\Tiho\bank-profile"
if not exist "%TIHO_PROFILE%" mkdir "%TIHO_PROFILE%"
if not exist "%TIHO_PROFILE%\user.js" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$root=(Resolve-Path '%~dp0').Path; $ui=[Uri]::new((Join-Path $root 'ui\index.html')).AbsoluteUri; $line='user_pref("browser.startup.homepage", '+(ConvertTo-Json -Compress $ui)+');'; Set-Content -LiteralPath (Join-Path '%TIHO_PROFILE%' 'user.js') -Value $line -Encoding ascii"
)
start "" "%~dp0firefox\firefox.exe" -no-remote -profile "%TIHO_PROFILE%" %*
