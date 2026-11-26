@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo Pornesc FermApp in modul de test Firebase.
echo Inchide mai intai fereastra "FermApp - Telefon", daca este deschisa.
echo.

for /f %%i in ('node scripts\prepare-local.mjs') do set REACT_NATIVE_PACKAGER_HOSTNAME=%%i
set EXPO_PUBLIC_BACKEND=firebase

start "FermApp - Firebase (scaneaza codul QR)" cmd /k "cd apps\mobile && npx expo start --go --lan --clear"
