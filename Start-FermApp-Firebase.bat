@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo Starting FermApp in Firebase test mode.
echo First close the "FermApp - Phone" window, if it is open.
echo.

for /f %%i in ('node scripts\prepare-local.mjs') do set REACT_NATIVE_PACKAGER_HOSTNAME=%%i
set EXPO_PUBLIC_BACKEND=firebase

start "FermApp - Firebase (scan the QR code)" cmd /k "cd apps\mobile && npx expo start --go --lan --clear"
