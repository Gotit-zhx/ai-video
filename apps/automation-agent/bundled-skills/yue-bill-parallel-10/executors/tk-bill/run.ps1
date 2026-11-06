$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding

$SkillRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$SharedRuntimeRoot = Join-Path $env:LOCALAPPDATA "tk-bill"
$RuntimeRoot = if ($env:YUE_BILL_WORKER_STATE_ROOT) {
    Join-Path $env:YUE_BILL_WORKER_STATE_ROOT "tk-bill"
} else {
    $SharedRuntimeRoot
}
$VenvDir = Join-Path $SharedRuntimeRoot ".venv"
$Requirements = Join-Path $SkillRoot "requirements.txt"

New-Item -ItemType Directory -Path $RuntimeRoot -Force | Out-Null
New-Item -ItemType Directory -Path $SharedRuntimeRoot -Force | Out-Null

function Get-PythonCommand {
    $candidates = @(
        @{ Command = "py"; Args = @("-3") },
        @{ Command = "python"; Args = @() },
        @{ Command = "python3"; Args = @() }
    )
    foreach ($candidate in $candidates) {
        $command = Get-Command $candidate.Command -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            try {
                & $command.Source @($candidate.Args) -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)"
                if ($LASTEXITCODE -eq 0) {
                    return @{ Path = $command.Source; Args = $candidate.Args }
                }
            } catch {
                continue
            }
        }
    }
    return $null
}

$python = Get-PythonCommand
if ($null -eq $python) {
    $winget = Get-Command winget -ErrorAction SilentlyContinue
    if ($null -eq $winget) {
        throw "Python 3.10+ is required. Install Python from python.org or install winget, then run this command again."
    }
    Write-Host "Python 3.10+ not found. Installing it with winget..."
    & $winget.Source install --id Python.Python.3.12 --exact --scope user --accept-source-agreements --accept-package-agreements
    if ($LASTEXITCODE -ne 0) {
        throw "Python installation failed with exit code $LASTEXITCODE."
    }
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "User") + ";" + [Environment]::GetEnvironmentVariable("Path", "Machine")
    $python = Get-PythonCommand
    if ($null -eq $python) {
        throw "Python was installed, but it is not available in PATH yet. Open a new PowerShell window and run this command again."
    }
}

$venvPython = Join-Path $VenvDir "Scripts\python.exe"
$venvHealthy = $false
if (Test-Path -LiteralPath $venvPython) {
    & $venvPython -c "import openpyxl, selenium, websocket"
    $venvHealthy = ($LASTEXITCODE -eq 0)
}

if (-not $venvHealthy) {
    # Recover only an absent or broken environment. Healthy environments are
    # reused so ordinary exports do not delete files under AppData.
    if (Test-Path -LiteralPath $VenvDir) {
        Write-Host "Rebuilding isolated Python environment..."
        Remove-Item -LiteralPath $VenvDir -Recurse -Force
    }

    Write-Host "Creating isolated Python environment..."
    & $python.Path @($python.Args) -m venv $VenvDir
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create the skill virtual environment."
    }

    Write-Host "Installing Python dependencies..."
    & $venvPython -m pip install --disable-pip-version-check -r $Requirements
    if ($LASTEXITCODE -ne 0) {
        throw "Dependency installation failed with exit code $LASTEXITCODE."
    }
} else {
    Write-Host "Reusing isolated Python environment..."
}

$env:TK_BILL_RUNTIME_DIR = $RuntimeRoot
$env:PYTHONIOENCODING = "utf-8"
& $venvPython -u (Join-Path $SkillRoot "scripts\feikua_store.py") @args
exit $LASTEXITCODE
