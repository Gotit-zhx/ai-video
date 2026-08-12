[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Workbook,

    [Alias("start-date")]
    [string]$StartDate,

    [Alias("end-date")]
    [string]$EndDate,

    [Alias("output-root")]
    [string]$OutputRoot,

    [string]$TasksPath,
    [switch]$Execute,
    [switch]$Resume,
    [switch]$ResumeSubmittedShopee,
    [switch]$RecoveryPass,
    [switch]$AdCostsOnlyRecovery,
    [switch]$IgnoreExistingRecoveryBackoff,
    [switch]$AllowPreservedSessionEviction,

    [ValidateRange(1, 10)]
    [int]$MaxRecoveryAttempts = 3,

    [ValidateRange(2, 60)]
    [int]$WorkerSilenceTimeoutMinutes = 8,

    [switch]$GeneratePreviewWorkbook,

    [ValidateSet("all", "tiktok", "tiktok-global", "tiktok-all", "shopee", "lazada")]
    [string]$OnlyPlatform = "all",

    [switch]$OfficialWarehouseOnly,

    [string]$OnlyStore,
    [string]$OnlyTaskId,
    [string]$OnlySheet,
    [string]$ExcludeSheet,
    [string]$OnlyStatuses,
    [switch]$SkipGrayRows,

    [ValidateRange(1, 5)]
    [int]$MaxConcurrency = 3,

    [ValidateRange(1, 5)]
    [int]$MaxLiveStoreSessions = 5,

    [ValidateRange(0, 10000)]
    [int]$MaxTasks = 0,

    [ValidateRange(0, 60)]
    [int]$StartupStaggerSeconds = 5,

    [ValidateRange(0, 128)]
    [double]$MinimumFreeMemoryGB = 2,

    [ValidateRange(0, 128)]
    [double]$MinimumLaunchFreeMemoryGB = 2,

    [switch]$AllowLowMemory,

    [switch]$WaitForInitialWave
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding

$skillRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$serialRun = Join-Path $skillRoot "serial-run.ps1"
$parallelRun = Join-Path $skillRoot "scripts\parallel_runner.ps1"
$workerRun = Join-Path $skillRoot "scripts\run_worker.ps1"
$runtimeInit = Join-Path $skillRoot "scripts\initialize_runtimes.ps1"

function Add-OptionalArgument {
    param(
        [System.Collections.Generic.List[string]]$Arguments,
        [string]$Name,
        [object]$Value
    )
    if ($null -ne $Value -and -not [string]::IsNullOrWhiteSpace([string]$Value)) {
        $Arguments.Add($Name)
        $Arguments.Add([string]$Value)
    }
}

function Invoke-SerialPlan {
    $arguments = [System.Collections.Generic.List[string]]::new()
    $arguments.Add("-NoProfile")
    $arguments.Add("-ExecutionPolicy")
    $arguments.Add("Bypass")
    $arguments.Add("-File")
    $arguments.Add($serialRun)
    Add-OptionalArgument $arguments "-Workbook" $Workbook
    Add-OptionalArgument $arguments "-StartDate" $StartDate
    Add-OptionalArgument $arguments "-EndDate" $EndDate
    Add-OptionalArgument $arguments "-OutputRoot" $OutputRoot
    Add-OptionalArgument $arguments "-TasksPath" $TasksPath
    Add-OptionalArgument $arguments "-OnlyPlatform" $OnlyPlatform
    Add-OptionalArgument $arguments "-OnlyStore" $OnlyStore
    Add-OptionalArgument $arguments "-OnlySheet" $OnlySheet
    Add-OptionalArgument $arguments "-ExcludeSheet" $ExcludeSheet
    Add-OptionalArgument $arguments "-OnlyStatuses" $OnlyStatuses
    if ($SkipGrayRows) {
        $arguments.Add("-SkipGrayRows")
    }
    if ($Resume) {
        $arguments.Add("-Resume")
    }
    if ($GeneratePreviewWorkbook) {
        $arguments.Add("-GeneratePreviewWorkbook")
    }
    & powershell @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Task planning failed with exit code $LASTEXITCODE."
    }
}

if (-not $Execute) {
    Invoke-SerialPlan
    exit 0
}

if (-not $TasksPath) {
    Invoke-SerialPlan
    if (-not $OutputRoot) {
        if (-not $Workbook -or -not $StartDate -or -not $EndDate) {
            throw "-Workbook, -StartDate, and -EndDate are required for a new execution."
        }
        $resolvedWorkbook = (Resolve-Path -LiteralPath $Workbook).Path
        $OutputRoot = Join-Path (
            Split-Path -Parent $resolvedWorkbook
        ) "monthly-bill-run_${StartDate}_${EndDate}"
    }
    $TasksPath = Join-Path (
        [System.IO.Path]::GetFullPath($OutputRoot)
    ) "_run\tasks.json"
}
$TasksPath = (Resolve-Path -LiteralPath $TasksPath).Path

& powershell -NoProfile -ExecutionPolicy Bypass -File $runtimeInit -SkillRoot $skillRoot
if ($LASTEXITCODE -ne 0) {
    throw "Parallel runtime initialization failed with exit code $LASTEXITCODE."
}

$parallelArguments = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $parallelRun,
    "-TasksPath", $TasksPath,
    "-SerialRun", $serialRun,
    "-WorkerRun", $workerRun,
    "-MaxConcurrency", $MaxConcurrency,
    "-MaxTasks", $MaxTasks,
    "-StartupStaggerSeconds", $StartupStaggerSeconds,
    "-MinimumFreeMemoryGB", $MinimumFreeMemoryGB,
    "-MinimumLaunchFreeMemoryGB", $MinimumLaunchFreeMemoryGB,
    "-WorkerSilenceTimeoutMinutes", $WorkerSilenceTimeoutMinutes,
    "-MaxLiveStoreSessions", $MaxLiveStoreSessions,
    "-OnlyPlatform", $OnlyPlatform
)
if ($OnlyStore) {
    $parallelArguments += @("-OnlyStore", $OnlyStore)
}
if ($OfficialWarehouseOnly) {
    $parallelArguments += "-OfficialWarehouseOnly"
}
if ($OnlyTaskId) {
    $parallelArguments += @("-OnlyTaskId", $OnlyTaskId)
}
if ($OnlySheet) {
    $parallelArguments += @("-OnlySheet", $OnlySheet)
}
if ($OnlyStatuses) {
    $parallelArguments += @("-OnlyStatuses", $OnlyStatuses)
}
if ($AllowLowMemory) {
    $parallelArguments += "-AllowLowMemory"
}
if ($ResumeSubmittedShopee) {
    $parallelArguments += "-ResumeSubmittedShopee"
}
if ($RecoveryPass) {
    $parallelArguments += @(
        "-RecoveryPass",
        "-MaxRecoveryAttempts", $MaxRecoveryAttempts
    )
}
if ($AdCostsOnlyRecovery) {
    $parallelArguments += "-AdCostsOnlyRecovery"
}
if ($IgnoreExistingRecoveryBackoff) {
    $parallelArguments += "-IgnoreExistingRecoveryBackoff"
}
if ($AllowPreservedSessionEviction) {
    $parallelArguments += "-AllowPreservedSessionEviction"
}
if ($WaitForInitialWave) {
    $parallelArguments += "-WaitForInitialWave"
}

& powershell @parallelArguments
if ($LASTEXITCODE -ne 0) {
    throw "Parallel execution failed with exit code $LASTEXITCODE."
}

& powershell -NoProfile -ExecutionPolicy Bypass -File $serialRun `
    -TasksPath $TasksPath `
    -FinalizeOnly
if ($LASTEXITCODE -ne 0) {
    throw "Workbook finalization failed with exit code $LASTEXITCODE."
}

Write-Host "YUE_BILL_PARALLEL_10_COMPLETE"
Write-Host "tasks_path: $TasksPath"
