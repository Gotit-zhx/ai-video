[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$ShopName,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingArguments
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding

$SharedRun = Join-Path (Split-Path -Parent $PSScriptRoot) "tk-bill\run.ps1"
if (-not (Test-Path -LiteralPath $SharedRun -PathType Leaf)) {
    throw "The shared Feikua connection kernel was not found at $SharedRun"
}

function Get-ShopeeSession {
    # An idle shop intentionally makes --discover-port exit nonzero. Capture
    # that result without letting this launcher's Stop preference abort the
    # automatic-start branch.
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $discovery = @(& powershell -ExecutionPolicy Bypass -File $SharedRun $ShopName --discover-port 2>&1)
        $discoveryExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($discoveryExit -ne 0) {
        Write-Host "The exact shop is idle; starting its isolated session..."
        $previousErrorAction = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $connection = @(& powershell -ExecutionPolicy Bypass -File $SharedRun $ShopName --connect-only 2>&1)
            $connectionExit = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorAction
        }
        $connection | Write-Host
        if ($connectionExit -ne 0) {
            exit $connectionExit
        }

        # connect-only already reports the exact debugger port and core version.
        # Reuse it instead of launching another discovery process.
        $discovery = $connection
        $connectedPort = $discovery | Where-Object {
            $_ -match "^\s*debugger_port:\s*(\d+)\s*$"
        } | Select-Object -Last 1
        $connectedCore = $discovery | Where-Object {
            $_ -match "^\s*core_version:\s*(.+?)\s*$"
        } | Select-Object -Last 1
        if ($null -eq $connectedPort -or $null -eq $connectedCore) {
            Write-Host "CONNECT_OUTPUT_INCOMPLETE_FALLING_BACK_TO_CDP_DISCOVERY"
            $discovery = @(& powershell -ExecutionPolicy Bypass -File $SharedRun $ShopName --discover-port)
            if ($LASTEXITCODE -ne 0) {
                exit $LASTEXITCODE
            }
        } else {
            Write-Host "SHOPEE_SESSION_REUSED_FROM_CONNECT_OUTPUT"
        }
    }
    $discovery | Write-Host

    $portLine = $discovery | Where-Object {
        $_ -match "^\s*(?:DISCOVERED_CDP_PORT|debugger_port):\s*(\d+)\s*$"
    } | Select-Object -Last 1
    $coreLine = $discovery | Where-Object {
        $_ -match "^\s*core_version:\s*(.+?)\s*$"
    } | Select-Object -Last 1
    if ($null -eq $portLine -or $null -eq $coreLine) {
        throw "Could not parse the current Shopee CDP port or browser core."
    }

    return [PSCustomObject]@{
        Port = [int]([regex]::Match($portLine, ":\s*(\d+)\s*$").Groups[1].Value)
        Core = ([regex]::Match($coreLine, "^\s*core_version:\s*(.+?)\s*$")).Groups[1].Value
    }
}

function Stop-ShopeeSession {
    param(
        [string]$SuccessMarker,
        [string]$RetryMessage,
        [switch]$BestEffort
    )
    $stopExitCode = 1
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $previousErrorAction = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $stopOutput = @(& powershell -ExecutionPolicy Bypass -File $SharedRun $ShopName --stop 2>&1)
            $stopExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorAction
        }
        $stopOutput | Write-Host
        if ($stopExitCode -eq 0) {
            Write-Host $SuccessMarker
            return
        }
        if ($BestEffort -and ($stopOutput -match "Feikua local WebDriver service was not found")) {
            # The desktop service is already unavailable/restarting, so repeating
            # the same discovery command only adds its full connection timeout.
            Write-Host "SHOPEE_SESSION_CLOSE_SKIPPED_DURING_FEIKUA_SERVICE_RESTART"
            return
        }
        if ($attempt -lt 3) {
            Write-Host "$RetryMessage ($attempt/3)..."
            Start-Sleep -Seconds 2
        }
    }
    if ($BestEffort) {
        # The export result has already been confirmed by both completed files.
        # A transient Feikua service restart must not turn that successful export
        # into a failed task; the next executor will still enforce idle handoff.
        Write-Host "SHOPEE_SESSION_CLOSE_UNCONFIRMED_AFTER_CONFIRMED_DOWNLOADS"
        return
    }
    throw "The Feikua Shopee shop session could not be closed after 3 attempts."
}

function Invoke-ShopeeIncome {
    param(
        [string[]]$ScriptArguments,
        [switch]$CloseOnSuccess
    )

    $session = Get-ShopeeSession
    $venvPython = Join-Path $env:LOCALAPPDATA "tk-bill\.venv\Scripts\python.exe"
    if (-not (Test-Path -LiteralPath $venvPython -PathType Leaf)) {
        throw "The shared tk-bill Python runtime was not found at $venvPython"
    }
    $env:PYTHONIOENCODING = "utf-8"
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $venvPython -u (Join-Path $PSScriptRoot "scripts\shopee_income.py") `
            --port $session.Port --core $session.Core @ScriptArguments
        $exportExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($exportExitCode -eq 22) {
        Stop-ShopeeSession `
            -SuccessMarker "SHOPEE_INVALID_PASSWORD_CONNECTION_STOPPED" `
            -RetryMessage "Shopee rejected the stored password; closing the exact shop session temporarily failed, retrying"
        Write-Host "SHOPEE_MANUAL_EXPORT_REQUIRED"
        exit 22
    }
    if ($exportExitCode -eq 23) {
        Stop-ShopeeSession `
            -SuccessMarker "SHOPEE_MISSING_PASSWORD_CONNECTION_STOPPED" `
            -RetryMessage "Shopee has no stored in-site password; closing the exact shop session temporarily failed, retrying"
        Write-Host "SHOPEE_MANUAL_EXPORT_REQUIRED"
        exit 23
    }
    if ($exportExitCode -eq 24) {
        Stop-ShopeeSession `
            -SuccessMarker "SHOPEE_SELLER_HOME_NOT_REACHED_CONNECTION_STOPPED" `
            -RetryMessage "Shopee did not reach Seller Center home; closing the exact shop session temporarily failed, retrying"
        Write-Host "SHOPEE_MANUAL_EXPORT_REQUIRED"
        exit 24
    }
    if ($exportExitCode -eq 25) {
        Stop-ShopeeSession `
            -SuccessMarker "SHOPEE_PASSWORD_VERIFICATION_NOT_COMPLETED_CONNECTION_STOPPED" `
            -RetryMessage "Shopee in-site password verification did not finish; closing the exact shop session temporarily failed, retrying"
        Write-Host "SHOPEE_MANUAL_EXPORT_REQUIRED"
        exit 25
    }
    if ($exportExitCode -ne 0 -or -not $CloseOnSuccess) {
        exit $exportExitCode
    }

    Stop-ShopeeSession `
        -SuccessMarker "SHOPEE_SESSION_CLOSED_AFTER_CONFIRMED_DOWNLOADS" `
        -RetryMessage "Shopee export succeeded, but closing the Feikua session temporarily failed; retrying" `
        -BestEffort
    exit 0
}

$arguments = @($RemainingArguments | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($arguments.Count -eq 0) {
    $arguments = @("--connect-only")
}

if ($arguments[0] -eq "--open-income-date-picker") {
    if ($arguments.Count -ne 1) {
        throw "--open-income-date-picker does not accept date arguments."
    }
    Invoke-ShopeeIncome -ScriptArguments @()
}

if ($arguments[0] -eq "--export-income") {
    if ($arguments.Count -ne 3) {
        throw "Usage: --export-income YYYY-MM-DD YYYY-MM-DD"
    }
    Invoke-ShopeeIncome -ScriptArguments @(
        "--shop-name", $ShopName,
        "--start", $arguments[1],
        "--end", $arguments[2]
    ) -CloseOnSuccess
}

if ($arguments[0] -eq "--export-both") {
    if ($arguments.Count -ne 3) {
        throw "Usage: --export-both YYYY-MM-DD YYYY-MM-DD"
    }
    Invoke-ShopeeIncome -ScriptArguments @(
        "--workflow", "both",
        "--shop-name", $ShopName,
        "--start", $arguments[1],
        "--end", $arguments[2]
    ) -CloseOnSuccess
}

if ($arguments[0] -eq "--export-both-official-warehouse") {
    if ($arguments.Count -ne 3) {
        throw "Usage: --export-both-official-warehouse YYYY-MM-DD YYYY-MM-DD"
    }
    Invoke-ShopeeIncome -ScriptArguments @(
        "--workflow", "both",
        "--official-warehouse-orders",
        "--shop-name", $ShopName,
        "--start", $arguments[1],
        "--end", $arguments[2]
    ) -CloseOnSuccess
}

if ($arguments[0] -eq "--resume-both") {
    if ($arguments.Count -ne 3) {
        throw "Usage: --resume-both YYYY-MM-DD YYYY-MM-DD"
    }
    Invoke-ShopeeIncome -ScriptArguments @(
        "--workflow", "both-resume",
        "--shop-name", $ShopName,
        "--start", $arguments[1],
        "--end", $arguments[2]
    ) -CloseOnSuccess
}

if ($arguments[0] -eq "--resume-both-official-warehouse") {
    if ($arguments.Count -ne 3) {
        throw "Usage: --resume-both-official-warehouse YYYY-MM-DD YYYY-MM-DD"
    }
    Invoke-ShopeeIncome -ScriptArguments @(
        "--workflow", "both-resume",
        "--official-warehouse-orders",
        "--shop-name", $ShopName,
        "--start", $arguments[1],
        "--end", $arguments[2]
    ) -CloseOnSuccess
}

if ($arguments[0] -eq "--export-orders") {
    if ($arguments.Count -ne 3) {
        throw "Usage: --export-orders YYYY-MM-DD YYYY-MM-DD"
    }
    Invoke-ShopeeIncome -ScriptArguments @(
        "--workflow", "orders",
        "--shop-name", $ShopName,
        "--start", $arguments[1],
        "--end", $arguments[2]
    ) -CloseOnSuccess
}

if ($arguments[0] -in @("--export-balance", "--submit-balance")) {
    if ($arguments.Count -ne 3) {
        throw "Usage: --export-balance YYYY-MM-DD YYYY-MM-DD"
    }
    Invoke-ShopeeIncome -ScriptArguments @(
        "--workflow", "balance-export",
        "--shop-name", $ShopName,
        "--start", $arguments[1],
        "--end", $arguments[2]
    ) -CloseOnSuccess
}

if ($arguments[0] -eq "--download-balance") {
    if ($arguments.Count -ne 3) {
        throw "Usage: --download-balance YYYY-MM-DD YYYY-MM-DD"
    }
    Invoke-ShopeeIncome -ScriptArguments @(
        "--workflow", "balance-download",
        "--shop-name", $ShopName,
        "--start", $arguments[1],
        "--end", $arguments[2]
    ) -CloseOnSuccess
}

$connectionModes = @("--connect-only", "--inspect", "--discover-port", "--stop")
if ($arguments.Count -ne 1 -or $connectionModes -notcontains $arguments[0]) {
    throw "Supported modes: $($connectionModes -join ', '), --open-income-date-picker, --export-income START END, --export-both START END, --export-both-official-warehouse START END, --resume-both START END, --resume-both-official-warehouse START END, --export-orders START END, --export-balance START END, or --download-balance START END"
}

& powershell -ExecutionPolicy Bypass -File $SharedRun $ShopName @arguments
exit $LASTEXITCODE
