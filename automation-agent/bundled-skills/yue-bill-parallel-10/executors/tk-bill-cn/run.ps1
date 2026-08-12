$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding

$sharedRunner = Join-Path (Split-Path -Parent $PSScriptRoot) "tk-bill\run.ps1"
if (-not (Test-Path -LiteralPath $sharedRunner -PathType Leaf)) {
    throw "The shared tk-bill kernel was not found at $sharedRunner"
}

$previousProfile = $env:TK_BILL_SELLER_CENTER_PROFILE
try {
    $env:TK_BILL_SELLER_CENTER_PROFILE = "cross-border"
    & $sharedRunner @args
    exit $LASTEXITCODE
}
finally {
    $env:TK_BILL_SELLER_CENTER_PROFILE = $previousProfile
}
