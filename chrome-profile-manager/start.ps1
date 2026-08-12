$ErrorActionPreference = "Stop"
$appRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$bundledElectron = Join-Path $appRoot "..\automation-agent\node_modules\electron\dist\electron.exe"

if (Test-Path -LiteralPath $bundledElectron) {
  & $bundledElectron $appRoot
  exit $LASTEXITCODE
}

Write-Host "未找到项目内的 Electron，尝试使用 npm 安装依赖。" -ForegroundColor Yellow
Push-Location $appRoot
try {
  npm install
  npm start
} finally {
  Pop-Location
}
