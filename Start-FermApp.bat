@echo off
chcp 65001 >nul
cd /d "%~dp0"

if not exist node_modules (
  echo Installing components. The first start takes a few minutes...
  call npm install --no-audit --no-fund
)

for /f %%i in ('node scripts\prepare-local.mjs') do set REACT_NATIVE_PACKAGER_HOSTNAME=%%i

start "FermApp - Server (do not close)" cmd /k "npm run local -w @fermapp/api"
start "FermApp - Phone (scan the QR code)" cmd /k "cd apps\mobile && npx expo start --go --lan --clear"
