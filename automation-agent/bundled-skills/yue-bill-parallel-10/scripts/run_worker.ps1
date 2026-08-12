[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$TasksPath,

    [Parameter(Mandatory = $true)]
    [string]$SerialRun,

    [Parameter(Mandatory = $true)]
    [string]$RuntimeRoot,

    [Parameter(Mandatory = $true)]
    [string]$SessionFile,

    [Parameter(Mandatory = $true)]
    [string]$LifecycleLock,

    [string]$StartupBarrierRoot,

    [ValidateRange(0, 10)]
    [int]$StartupBarrierSize = 0,

    [switch]$ResumeSubmittedShopee
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding

$env:YUE_BILL_PARALLEL_MODE = "1"
$env:YUE_BILL_SESSION_FILE = [System.IO.Path]::GetFullPath($SessionFile)
$env:YUE_BILL_FEIKUA_LOCK_PATH = [System.IO.Path]::GetFullPath($LifecycleLock)
$env:YUE_BILL_WORKER_STATE_ROOT = Join-Path (
    Split-Path -Parent ([System.IO.Path]::GetFullPath($RuntimeRoot))
) "executor-state"
$env:YUE_BILL_DRIVER_CACHE_DIR = Join-Path (
    [Environment]::GetFolderPath("LocalApplicationData")
) "yue-bill-parallel\drivers"
$env:PYTHONIOENCODING = "utf-8"

$skillRoot = Split-Path -Parent $SerialRun

if ($StartupBarrierRoot -and $StartupBarrierSize -gt 1) {
    $barrierPlan = [System.IO.File]::ReadAllText(
        $TasksPath,
        [System.Text.Encoding]::UTF8
    ) | ConvertFrom-Json
    $barrierTask = @($barrierPlan.tasks)[0]
    $executorRunner = Join-Path (
        Join-Path $skillRoot "executors\$($barrierTask.executor)"
    ) "run.ps1"
    Write-Host (
        "PARALLEL_STARTUP_PRECONNECT: {0} target={1}" -f
        $barrierTask.storeName, $StartupBarrierSize
    )
    $connectOutput = @(
        & powershell -NoProfile -ExecutionPolicy Bypass `
            -File $executorRunner `
            $barrierTask.executorName `
            --connect-only 2>&1
    )
    $connectExitCode = $LASTEXITCODE
    $connectOutput | Write-Host

    if ($connectExitCode -ne 0) {
        $barrierTask.status = "failed"
        $barrierTask.statusText = [System.Text.Encoding]::UTF8.GetString(
            [System.Convert]::FromBase64String("5pyq5a6M5oiQ")
        )
        $barrierTask.terminalCode = "PARALLEL_PRECONNECT_FAILED"
        $barrierTask.message = (
            "The initial parallel preconnect failed before the startup " +
            "barrier was released."
        )
        $barrierTask.updatedAt = [datetime]::UtcNow.ToString("o")
        $barrierPlan.tasks[0] = $barrierTask
        $barrierJson = $barrierPlan | ConvertTo-Json -Depth 20
        [System.IO.File]::WriteAllText(
            $TasksPath,
            "$barrierJson`n",
            [System.Text.UTF8Encoding]::new($false)
        )
        Write-Host (
            "PARALLEL_STARTUP_REJECTED: {0} connect_exit={1}" -f
            $barrierTask.storeName, $connectExitCode
        )
        $preconnectCloseAttempt = 0
        while ($preconnectCloseAttempt -lt 3) {
            $preconnectCloseAttempt += 1
            $closeOutput = @(
                & powershell -NoProfile -ExecutionPolicy Bypass `
                    -File $executorRunner `
                    $barrierTask.executorName `
                    --stop 2>&1
            )
            $closeExitCode = $LASTEXITCODE
            $closeOutput | Write-Host
            if (
                $closeExitCode -eq 0 -or
                ($closeOutput -join "`n") -match (
                    "Feikua local WebDriver service was not found"
                )
            ) {
                break
            }
            Write-Host (
                "PARALLEL_PRECONNECT_CLOSE_RETRY: " +
                "attempt=$preconnectCloseAttempt exit=$closeExitCode"
            )
            Start-Sleep -Seconds 10
        }
        if (
            $preconnectCloseAttempt -ge 3 -and
            $closeExitCode -ne 0
        ) {
            Write-Host (
                "PARALLEL_PRECONNECT_CLOSE_RECOVERY_EXHAUSTED: " +
                "$($barrierTask.storeName)"
            )
        }
        exit $connectExitCode
    }

    New-Item -ItemType Directory -Path $StartupBarrierRoot -Force | Out-Null
    $readyPath = Join-Path $StartupBarrierRoot (
        "$($barrierTask.taskId).ready"
    )
    $readyTemporary = "$readyPath.tmp.$PID"
    [System.IO.File]::WriteAllText(
        $readyTemporary,
        (
            "store={0}`nexit={1}`nreadyAt={2}`n" -f
            $barrierTask.storeName,
            $connectExitCode,
            [datetime]::UtcNow.ToString("o")
        ),
        [System.Text.UTF8Encoding]::new($false)
    )
    Move-Item -LiteralPath $readyTemporary -Destination $readyPath -Force
    Write-Host (
        "PARALLEL_STARTUP_READY: {0} connect_exit={1}" -f
        $barrierTask.storeName, $connectExitCode
    )

    $lastBarrierProgress = [datetime]::MinValue
    while (-not (Test-Path -LiteralPath (
        Join-Path $StartupBarrierRoot "released"
    ))) {
        $currentBarrierSize = $StartupBarrierSize
        $barrierTargetPath = Join-Path $StartupBarrierRoot "target.txt"
        if (Test-Path -LiteralPath $barrierTargetPath -PathType Leaf) {
            $parsedBarrierSize = 0
            if ([int]::TryParse(
                [System.IO.File]::ReadAllText(
                    $barrierTargetPath,
                    [System.Text.Encoding]::UTF8
                ).Trim(),
                [ref]$parsedBarrierSize
            ) -and $parsedBarrierSize -gt 0) {
                $currentBarrierSize = $parsedBarrierSize
            }
        }
        $readyCount = @(
            Get-ChildItem -LiteralPath $StartupBarrierRoot `
                -File `
                -Filter "*.ready" `
                -ErrorAction SilentlyContinue
        ).Count
        if ($readyCount -ge $currentBarrierSize) {
            $releasePath = Join-Path $StartupBarrierRoot "released"
            if (-not (Test-Path -LiteralPath $releasePath)) {
                [System.IO.File]::WriteAllText(
                    $releasePath,
                    [datetime]::UtcNow.ToString("o"),
                    [System.Text.UTF8Encoding]::new($false)
                )
            }
            break
        }
        if (
            ([datetime]::UtcNow - $lastBarrierProgress).TotalSeconds -ge 10
        ) {
            Write-Host (
                "PARALLEL_STARTUP_WAITING: ready={0}/{1}" -f
                $readyCount, $currentBarrierSize
            )
            $lastBarrierProgress = [datetime]::UtcNow
        }
        Start-Sleep -Milliseconds 250
    }
    Write-Host (
        "PARALLEL_STARTUP_BARRIER_RELEASED: {0}" -f
        $barrierTask.storeName
    )
}

$arguments = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $SerialRun,
    "-TasksPath", $TasksPath,
    "-Execute",
    "-Resume",
    "-SkipFinalizer",
    "-RuntimeRootOverride", $RuntimeRoot
)
if ($ResumeSubmittedShopee) {
    $arguments += "-ResumeSubmittedShopee"
}

& powershell @arguments
$workerExitCode = $LASTEXITCODE

try {
    $workerPlan = [System.IO.File]::ReadAllText(
        $TasksPath,
        [System.Text.Encoding]::UTF8
    ) | ConvertFrom-Json
    $task = @($workerPlan.tasks)[0]
    $sessionDisposition = if (
        $null -ne $task.PSObject.Properties["sessionDisposition"]
    ) {
        [string]$task.sessionDisposition
    } else {
        ""
    }
    if (
        $task.status -in @(
            "completed",
            "no_data",
            "failed",
            "manual_required"
        ) -and
        $task.terminalCode -notin @(
            "TIKTOK_EXPORT_SUBMITTED_SESSION_PRESERVED",
            "TIKTOK_AD_COST_MANUAL_VERIFICATION_SESSION_PRESERVED"
        ) -and
        $sessionDisposition -notin @(
            "preserved_recoverable",
            "preserved_manual"
        )
    ) {
        $executorRunner = Join-Path (
            Join-Path $skillRoot "executors\$($task.executor)"
        ) "run.ps1"
        $closeAttempt = 0
        while ($closeAttempt -lt 3) {
            $closeAttempt += 1
            $closeOutput = @(
                & powershell -NoProfile -ExecutionPolicy Bypass `
                    -File $executorRunner `
                    $task.executorName `
                    --stop 2>&1
            )
            $closeExitCode = $LASTEXITCODE
            $closeOutput | Write-Host
            if (
                $closeExitCode -eq 0 -or
                ($closeOutput -join "`n") -match (
                    "Feikua local WebDriver service was not found"
                )
            ) {
                Write-Host "PARALLEL_EXACT_STORE_IDLE_CONFIRMED"
                break
            }
            Write-Host (
                "PARALLEL_HANDOFF_CLOSE_RETRY: " +
                "attempt=$closeAttempt exit=$closeExitCode"
            )
            Start-Sleep -Seconds 10
        }
        if ($closeAttempt -ge 3 -and $closeExitCode -ne 0) {
            Write-Host (
                "PARALLEL_HANDOFF_CLOSE_RECOVERY_EXHAUSTED: " +
                "$($task.storeName)"
            )
        }
    } elseif (
        $sessionDisposition -in @(
            "preserved_recoverable",
            "preserved_manual"
        )
    ) {
        Write-Host (
            "PARALLEL_EXACT_STORE_SESSION_PRESERVED: " +
            "$($task.storeName) disposition=$sessionDisposition"
        )
    }
} catch {
    Write-Host "PARALLEL_HANDOFF_CHECK_FAILED: $($_.Exception.Message)"
}

exit $workerExitCode
