[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SkillRoot
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding

function Resolve-Python {
    $candidates = @(
        @{ Command = "py"; Args = @("-3") },
        @{ Command = "python"; Args = @() },
        @{ Command = "python3"; Args = @() }
    )
    foreach ($candidate in $candidates) {
        $command = Get-Command $candidate.Command -ErrorAction SilentlyContinue
        if ($null -eq $command) {
            continue
        }
        & $command.Source @($candidate.Args) -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)"
        if ($LASTEXITCODE -eq 0) {
            return @{ Path = $command.Source; Args = $candidate.Args }
        }
    }
    throw "Python 3.10+ is required."
}

function Initialize-Runtime {
    param(
        [string]$RuntimeName,
        [string]$Requirements,
        [string]$ImportCheck
    )
    $runtimeRoot = Join-Path $env:LOCALAPPDATA $RuntimeName
    $venvRoot = Join-Path $runtimeRoot ".venv"
    $venvPython = Join-Path $venvRoot "Scripts\python.exe"
    $healthy = $false
    if (Test-Path -LiteralPath $venvPython -PathType Leaf) {
        & $venvPython -c $ImportCheck
        $healthy = $LASTEXITCODE -eq 0
    }
    if ($healthy) {
        Write-Host "PARALLEL_RUNTIME_READY: $RuntimeName"
        return
    }

    $resolvedRuntimeRoot = [System.IO.Path]::GetFullPath($runtimeRoot)
    $resolvedLocalAppData = [System.IO.Path]::GetFullPath($env:LOCALAPPDATA)
    if (-not $resolvedRuntimeRoot.StartsWith(
        $resolvedLocalAppData,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw "Refusing to rebuild a runtime outside LOCALAPPDATA: $resolvedRuntimeRoot"
    }
    if (Test-Path -LiteralPath $venvRoot) {
        Remove-Item -LiteralPath $venvRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
    $python = Resolve-Python
    & $python.Path @($python.Args) -m venv $venvRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create runtime: $RuntimeName"
    }
    & $venvPython -m pip install --disable-pip-version-check -r $Requirements
    if ($LASTEXITCODE -ne 0) {
        throw "Could not install runtime dependencies: $RuntimeName"
    }
    & $venvPython -c $ImportCheck
    if ($LASTEXITCODE -ne 0) {
        throw "Runtime validation failed: $RuntimeName"
    }
    Write-Host "PARALLEL_RUNTIME_READY: $RuntimeName"
}

Initialize-Runtime `
    -RuntimeName "tk-bill" `
    -Requirements (Join-Path $SkillRoot "executors\tk-bill\requirements.txt") `
    -ImportCheck "import openpyxl, selenium, websocket"
Initialize-Runtime `
    -RuntimeName "lazada-bill" `
    -Requirements (Join-Path $SkillRoot "executors\lazada-bill\requirements.txt") `
    -ImportCheck "import selenium, websocket"

Write-Host "PARALLEL_RUNTIMES_INITIALIZED"
