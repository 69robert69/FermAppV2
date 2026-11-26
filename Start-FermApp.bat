@echo off
chcp 65001 >nul
cd /d "%~dp0"

if not exist node_modules (
  echo Instalez componentele. Prima pornire dureaza cateva minute...
  call npm install --no-audit --no-fund
)

for /f %%i in ('node scripts\prepare-local.mjs') do set REACT_NATIVE_PACKAGER_HOSTNAME=%%i

start "FermApp - Server (nu inchide)" cmd /k "npm run local -w @fermapp/api"
start "FermApp - Telefon (scaneaza codul QR)" cmd /k "cd apps\mobile && npx expo start --go --lan --clear"
