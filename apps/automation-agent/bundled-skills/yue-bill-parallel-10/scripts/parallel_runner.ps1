[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$TasksPath,

    [Parameter(Mandatory = $true)]
    [string]$SerialRun,

    [Parameter(Mandatory = $true)]
    [string]$WorkerRun,

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
    [switch]$ResumeSubmittedShopee,
    [switch]$RecoveryPass,
    [switch]$AdCostsOnlyRecovery,
    [switch]$IgnoreExistingRecoveryBackoff,
    [switch]$AllowPreservedSessionEviction,

    [ValidateRange(1, 10)]
    [int]$MaxRecoveryAttempts = 3,

    [ValidateRange(2, 60)]
    [int]$WorkerSilenceTimeoutMinutes = 8,

    [switch]$WaitForInitialWave,

    [ValidateSet("all", "tiktok", "tiktok-global", "tiktok-all", "shopee", "lazada")]
    [string]$OnlyPlatform = "all",

    [switch]$OfficialWarehouseOnly,

    [string]$OnlyStore,
    [string]$OnlyTaskId,
    [string]$OnlySheet,
    [string]$OnlyStatuses
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$EffectiveLiveStoreSessions = [math]::Min(
    $MaxConcurrency,
    $MaxLiveStoreSessions
)

function Read-JsonUtf8 {
    param([string]$Path)
    return [System.IO.File]::ReadAllText(
        $Path,
        [System.Text.Encoding]::UTF8
    ) | ConvertFrom-Json
}

function Save-JsonAtomic {
    param([string]$Path, [object]$Value)
    $temporary = "$Path.tmp.$PID"
    $json = $Value | ConvertTo-Json -Depth 20
    [System.IO.File]::WriteAllText(
        $temporary,
        "$json`n",
        [System.Text.UTF8Encoding]::new($false)
    )
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Get-FreePhysicalMemoryGB {
    try {
        $os = Get-CimInstance Win32_OperatingSystem
        return [math]::Round(($os.FreePhysicalMemory * 1KB) / 1GB, 2)
    } catch {
        return $null
    }
}

function Test-PathWithinRoot {
    param([string]$Path, [string]$Root)
    if (-not $Path -or -not $Root) {
        return $false
    }
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    return [string]::Equals(
        $fullPath,
        $fullRoot,
        [System.StringComparison]::OrdinalIgnoreCase
    ) -or $fullPath.StartsWith(
        "$fullRoot$([System.IO.Path]::DirectorySeparatorChar)",
        [System.StringComparison]::OrdinalIgnoreCase
    )
}

function Test-ValidOutputFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $false
    }
    if ((Get-Item -LiteralPath $Path).Length -le 0) {
        return $false
    }
    if ([System.IO.Path]::GetExtension($Path) -ine ".xlsx") {
        return $true
    }
    $archive = $null
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
        return $null -ne $archive.GetEntry("xl/workbook.xml")
    } catch {
        return $false
    } finally {
        if ($null -ne $archive) {
            $archive.Dispose()
        }
    }
}

function Set-ObjectProperty {
    param([object]$Object, [string]$Name, [AllowNull()][object]$Value)
    if ($null -eq $Object.PSObject.Properties[$Name]) {
        $Object | Add-Member -MemberType NoteProperty -Name $Name -Value $Value
    } else {
        $Object.$Name = $Value
    }
}

function Test-RecoverableTask {
    param(
        [object]$Task,
        [int]$MaximumAttempts
    )
    if (
        $Task.terminalCode -in @(
            "TIKTOK_AD_LOGIN_OTP_REQUIRED",
            "TIKTOK_AD_LOGIN_CREDENTIALS_REJECTED",
            "TIKTOK_SELLER_HOME_NOT_REACHED",
            "SHOPEE_SELLER_HOME_NOT_REACHED",
            "LAZADA_SELLER_HOME_NOT_REACHED",
            "STOPPED_AFTER_LOGIN_TIMEOUT"
        )
    ) {
        return $false
    }
    $attempts = if ($null -ne $Task.PSObject.Properties["recoveryAttempts"]) {
        [int]$Task.recoveryAttempts
    } else {
        0
    }
    if ($attempts -ge $MaximumAttempts) {
        return $false
    }
    if ($Task.status -in @("failed", "running", "pending", "planned")) {
        return $true
    }
    if (
        $AdCostsOnlyRecovery -and
        $Task.status -eq "manual_required" -and
        $Task.terminalCode -eq
            "TIKTOK_AD_COST_MANUAL_VERIFICATION_SESSION_PRESERVED"
    ) {
        return $true
    }
    return $false
}

function Get-RecoveryBackoffSeconds {
    param([int]$Attempt)
    if ($AdCostsOnlyRecovery) {
        return 30
    }
    if ($Attempt -le 1) {
        return 30
    }
    if ($Attempt -eq 2) {
        return 120
    }
    return 300
}

function Stop-StalledWorkerProcessTree {
    param([int]$RootProcessId)

    $allProcesses = @(Get-CimInstance Win32_Process)
    $descendants = [System.Collections.Generic.List[object]]::new()
    $queue = [System.Collections.Generic.Queue[object]]::new()
    $queue.Enqueue([pscustomobject]@{
        ProcessId = $RootProcessId
        Depth = 0
    })
    while ($queue.Count -gt 0) {
        $current = $queue.Dequeue()
        foreach ($child in @(
            $allProcesses | Where-Object {
                [int]$_.ParentProcessId -eq [int]$current.ProcessId
            }
        )) {
            $entry = [pscustomobject]@{
                ProcessId = [int]$child.ProcessId
                Name = [string]$child.Name
                Depth = [int]$current.Depth + 1
            }
            $descendants.Add($entry)
            $queue.Enqueue($entry)
        }
    }
    $targets = @(
        $descendants |
            Where-Object {
                $_.Name -in @(
                    "powershell.exe",
                    "python.exe",
                    "chromedriver.exe",
                    "conhost.exe"
                )
            } |
            Sort-Object Depth -Descending
    )
    $targets += [pscustomobject]@{
        ProcessId = $RootProcessId
        Name = "powershell.exe"
        Depth = 0
    }
    foreach ($target in $targets) {
        Stop-Process `
            -Id ([int]$target.ProcessId) `
            -Force `
            -ErrorAction SilentlyContinue
    }
}

function Stop-RecoveryExhaustedStoreSession {
    param(
        [object]$Task,
        [string]$SerialRunner
    )

    $skillRoot = Split-Path -Parent $SerialRunner
    $executorRun = Join-Path (
        Join-Path $skillRoot "executors\$($Task.executor)"
    ) "run.ps1"
    if (-not (Test-Path -LiteralPath $executorRun -PathType Leaf)) {
        Write-Host (
            "PARALLEL_EXHAUSTED_STORE_CLOSE_RUNNER_MISSING: " +
            "$($Task.storeName)"
        )
        return $false
    }
    $closeOutput = @(
        & powershell.exe -NoProfile -ExecutionPolicy Bypass `
            -File $executorRun `
            $Task.executorName `
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
        Write-Host (
            "PARALLEL_EXHAUSTED_STORE_SESSION_CLOSED: " +
            "$($Task.storeName)"
        )
        return $true
    }
    Write-Host (
        "PARALLEL_EXHAUSTED_STORE_SESSION_CLOSE_FAILED: {0} exit={1}" -f
        $Task.storeName,
        $closeExitCode
    )
    return $false
}

function Upgrade-MonthlyTaskPlan {
    param([object]$Plan, [string]$Path)
    if ([int]$Plan.schemaVersion -ge 6) {
        return $Plan
    }
    if (-not $Plan.outputRoot) {
        throw "The legacy task plan cannot be migrated because outputRoot is missing."
    }
    $balanceRoot = if ($Plan.balanceRoot) {
        [System.IO.Path]::GetFullPath([string]$Plan.balanceRoot)
    } else {
        [System.IO.Path]::GetFullPath(
            (Join-Path $Plan.outputRoot "收入板块")
        )
    }
    $adCostRoot = [System.IO.Path]::GetFullPath(
        (Join-Path $Plan.outputRoot "广告收入板块")
    )
    New-Item -ItemType Directory -Path $adCostRoot -Force | Out-Null

    foreach ($task in @($Plan.tasks)) {
        if (
            $task.executor -notin @("tk-bill", "tk-bill-cn") -and
            $task.platformKey -notin @("tiktok", "tiktok-global")
        ) {
            continue
        }
        if (
            -not $task.outputDir -or
            -not (Test-PathWithinRoot -Path $task.outputDir -Root $balanceRoot)
        ) {
            throw (
                "The legacy TikTok task output directory is outside balanceRoot: " +
                "$($task.storeName)"
            )
        }
        $balancePrefix = $balanceRoot.TrimEnd(
            [System.IO.Path]::DirectorySeparatorChar,
            [System.IO.Path]::AltDirectorySeparatorChar
        )
        $relativeOutput = [System.IO.Path]::GetFullPath(
            [string]$task.outputDir
        ).Substring($balancePrefix.Length).TrimStart(
            [System.IO.Path]::DirectorySeparatorChar,
            [System.IO.Path]::AltDirectorySeparatorChar
        )
        $adCostOutputDir = Join-Path $adCostRoot $relativeOutput
        New-Item -ItemType Directory -Path $adCostOutputDir -Force | Out-Null
        Set-ObjectProperty `
            -Object $task `
            -Name "adCostOutputDir" `
            -Value $adCostOutputDir

        $legacyStatus = "$($task.status)"
        if (
            $null -eq $task.PSObject.Properties["incomeStatus"] -or
            [string]::IsNullOrWhiteSpace([string]$task.incomeStatus)
        ) {
            $incomeStatus = if ($legacyStatus -eq "completed") {
                "completed"
            } elseif ($legacyStatus -eq "no_data") {
                "no_data"
            } else {
                "planned"
            }
            $incomeTerminalCode = if (
                $incomeStatus -in @("completed", "no_data")
            ) {
                $task.terminalCode
            } else {
                $null
            }
            Set-ObjectProperty -Object $task -Name "incomeStatus" -Value $incomeStatus
            Set-ObjectProperty `
                -Object $task `
                -Name "incomeTerminalCode" `
                -Value $incomeTerminalCode
        }
        if (
            $null -eq $task.PSObject.Properties["adCostStatus"] -or
            [string]::IsNullOrWhiteSpace([string]$task.adCostStatus)
        ) {
            Set-ObjectProperty -Object $task -Name "adCostStatus" -Value "planned"
        }
        foreach ($propertyName in @(
            "adCostTerminalCode",
            "adAccountCount",
            "adCostSummary"
        )) {
            if ($null -eq $task.PSObject.Properties[$propertyName]) {
                Set-ObjectProperty -Object $task -Name $propertyName -Value $null
            }
        }
        if ($null -eq $task.PSObject.Properties["adAccountExports"]) {
            Set-ObjectProperty -Object $task -Name "adAccountExports" -Value @()
        }
        if (
            $legacyStatus -in @("completed", "no_data") -and
            "$($task.adCostStatus)" -ne "completed"
        ) {
            $task.status = "planned"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("5b6F5omn6KGM")
            )
            $task.terminalCode = $null
            $task.message = "Income is terminal; advertising cost export is pending."
            Set-ObjectProperty `
                -Object $task `
                -Name "updatedAt" `
                -Value ([datetime]::UtcNow.ToString("o"))
        }
    }

    Set-ObjectProperty -Object $Plan -Name "schemaVersion" -Value 6
    Set-ObjectProperty -Object $Plan -Name "balanceRoot" -Value $balanceRoot
    Set-ObjectProperty -Object $Plan -Name "adCostRoot" -Value $adCostRoot
    Save-JsonAtomic -Path $Path -Value $Plan
    Write-Host "MONTHLY_TASK_PLAN_SCHEMA_UPGRADED: 6"
    return $Plan
}

function Quote-ProcessArgument {
    param([string]$Value)
    return '"{0}"' -f ($Value -replace '"', '\"')
}

function Get-TaskStoreKey {
    param([object]$Task)
    $name = if ($Task.executorName) { $Task.executorName } else { $Task.storeName }
    return ([string]$name).ToLowerInvariant()
}


function Test-PriorShopeeSubmissionEvidence {
    param([string]$WorkerRoot)

    if (-not (Test-Path -LiteralPath $WorkerRoot -PathType Container)) {
        return $false
    }
    $incomeSubmitted = $false
    $balanceSubmitted = $false
    foreach ($log in Get-ChildItem -LiteralPath $WorkerRoot -Filter "*.stdout.log" -File) {
        $content = Get-Content -LiteralPath $log.FullName -Encoding utf8 -Raw
        $incomeSubmitted = (
            $incomeSubmitted -or
            $content.Contains("SHOPEE_INCOME_EXPORT_SUBMITTED")
        )
        $balanceSubmitted = (
            $balanceSubmitted -or
            $content.Contains("SHOPEE_BALANCE_EXPORT_SUBMITTED")
        )
        if ($incomeSubmitted -and $balanceSubmitted) {
            return $true
        }
    }
    return $false
}

function Get-StoreKeyHash {
    param([string]$StoreKey)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($StoreKey)
        $hash = $sha.ComputeHash($bytes)
        return (
            ([System.BitConverter]::ToString($hash) -replace "-", "")
        ).Substring(0, 24).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-PlanRunFingerprint {
    param([object]$Plan)
    $source = [System.IO.Path]::GetFullPath(
        [string]$Plan.sourceWorkbook
    ).ToLowerInvariant()
    $output = [System.IO.Path]::GetFullPath(
        [string]$Plan.outputRoot
    ).ToLowerInvariant()
    return Get-StoreKeyHash -StoreKey (
        "$source|$output|$($Plan.startDate)|$($Plan.endDate)"
    )
}

function Test-ProcessMatchesStateEntry {
    param(
        [AllowNull()][object]$Process,
        [object]$Entry,
        [string]$ExpectedWorkerRun
    )
    if ($null -eq $Process) {
        return $false
    }
    $hasPreciseStartTime = [bool]$Entry.processStartTimeUtc
    $expectedText = if ($hasPreciseStartTime) {
        [string]$Entry.processStartTimeUtc
    } else {
        [string]$Entry.startedAt
    }
    if (-not $expectedText) {
        return $false
    }
    $expected = [datetime]::MinValue
    if (-not [datetime]::TryParse(
        $expectedText,
        [System.Globalization.CultureInfo]::InvariantCulture,
        [System.Globalization.DateTimeStyles]::RoundtripKind,
        [ref]$expected
    )) {
        return $false
    }
    $actual = $Process.StartTime.ToUniversalTime()
    $differenceMilliseconds = [math]::Abs(
        ($actual - $expected.ToUniversalTime()).TotalMilliseconds
    )
    $allowedMilliseconds = if ($hasPreciseStartTime) { 1000 } else { 30000 }
    if ($differenceMilliseconds -gt $allowedMilliseconds) {
        return $false
    }

    $expectedArguments = @(
        [string]$ExpectedWorkerRun,
        [string]$Entry.tasksPath
    ) | Where-Object { $_ }
    if ($expectedArguments.Count -gt 0) {
        try {
            $processInfo = Get-CimInstance `
                -ClassName Win32_Process `
                -Filter "ProcessId = $($Process.Id)" `
                -ErrorAction Stop
            $commandLine = [string]$processInfo.CommandLine
            if (-not $commandLine) {
                return $false
            }
            foreach ($expectedArgument in $expectedArguments) {
                if (
                    $commandLine.IndexOf(
                        $expectedArgument,
                        [System.StringComparison]::OrdinalIgnoreCase
                    ) -lt 0
                ) {
                    return $false
                }
            }
        } catch {
            return $false
        }
    }
    return $true
}

function Test-ConfirmedTask {
    param([object]$Task)
    if ($Task.status -ne "completed") {
        return $false
    }
    if ($Task.executor -in @("tk-bill", "tk-bill-cn")) {
        $incomeStatus = "$($Task.incomeStatus)".ToLowerInvariant()
        if ($incomeStatus -notin @("completed", "no_data")) {
            return $false
        }
        if (
            "$($Task.adCostStatus)".ToLowerInvariant() -ne "completed" -or
            -not $Task.adCostOutputDir -or
            $null -eq $Task.adAccountCount
        ) {
            return $false
        }
        $accountResults = @(
            $Task.adAccountExports | Where-Object { $null -ne $_ }
        )
        if ($accountResults.Count -ne [int]$Task.adAccountCount) {
            return $false
        }
        foreach ($accountResult in $accountResults) {
            if (
                [string]$accountResult.startDate -ne [string]$Task.startDate -or
                [string]$accountResult.endDate -ne [string]$Task.endDate
            ) {
                return $false
            }
            $accountStatus = "$($accountResult.status)".ToLowerInvariant()
            if ($accountStatus -eq "no_data") {
                continue
            }
            if ($accountStatus -ne "completed") {
                return $false
            }
            $accountFiles = @(
                $accountResult.filePaths | Where-Object { $_ }
            )
            if ($accountFiles.Count -eq 0 -and $accountResult.filePath) {
                $accountFiles = @([string]$accountResult.filePath)
            }
            if ($accountFiles.Count -eq 0) {
                return $false
            }
            foreach ($accountFile in $accountFiles) {
                if (
                    -not (Test-PathWithinRoot `
                        -Path $accountFile `
                        -Root $Task.adCostOutputDir) -or
                    -not (Test-ValidOutputFile -Path $accountFile) -or
                    (Test-Path -LiteralPath "$accountFile.crdownload") -or
                    (Test-Path -LiteralPath "$accountFile.tmp")
                ) {
                    return $false
                }
            }
        }
        $partialAdFiles = @(
            Get-ChildItem `
                -LiteralPath $Task.adCostOutputDir `
                -File `
                -ErrorAction SilentlyContinue |
                Where-Object {
                    $_.Name.EndsWith(
                        ".crdownload",
                        [System.StringComparison]::OrdinalIgnoreCase
                    ) -or $_.Name.EndsWith(
                        ".tmp",
                        [System.StringComparison]::OrdinalIgnoreCase
                    )
                }
        )
        if ($partialAdFiles.Count -gt 0) {
            return $false
        }
        if ($incomeStatus -eq "no_data") {
            return $true
        }
    }
    $files = @($Task.files | Where-Object { $_ })
    if ($files.Count -eq 0) {
        return $false
    }
    foreach ($file in $files) {
        if (-not (Test-ValidOutputFile -Path $file)) {
            return $false
        }
        if (Test-Path -LiteralPath "$file.crdownload") {
            return $false
        }
        $allowedRoots = @($Task.outputDir)
        if ($Task.balanceDetailOutputDir) {
            $allowedRoots += $Task.balanceDetailOutputDir
        }
        $withinAllowedRoot = $false
        foreach ($allowedRoot in $allowedRoots) {
            if (Test-PathWithinRoot -Path $file -Root $allowedRoot) {
                $withinAllowedRoot = $true
                break
            }
        }
        if (-not $withinAllowedRoot) {
            return $false
        }
    }
    $partialIncomeFiles = @(
        Get-ChildItem `
            -LiteralPath $Task.outputDir `
            -Filter "*.crdownload" `
            -File `
            -ErrorAction SilentlyContinue
    )
    if ($Task.balanceDetailOutputDir) {
        $partialIncomeFiles += @(
            Get-ChildItem `
                -LiteralPath $Task.balanceDetailOutputDir `
                -Filter "*.crdownload" `
                -File `
                -ErrorAction SilentlyContinue
        )
    }
    if ($partialIncomeFiles.Count -gt 0) {
        return $false
    }
    return $true
}

function Get-DeliveryKey {
    param([object]$Task)
    $paths = @(
        $Task.deliveryFilePath,
        $Task.balanceDetailDeliveryFilePath,
        $Task.adCostOutputDir
    ) | Where-Object { $_ } | ForEach-Object {
        [System.IO.Path]::GetFullPath([string]$_).ToLowerInvariant()
    }
    return $paths -join "|"
}

function Find-DuplicateTerminalSource {
    param(
        [object]$Plan,
        [object]$Task
    )
    $deliveryKey = Get-DeliveryKey -Task $Task
    if (-not $deliveryKey) {
        return $null
    }
    foreach ($candidate in @($Plan.tasks)) {
        if ($candidate.taskId -eq $Task.taskId) {
            continue
        }
        if (
            [string]$candidate.startDate -ne [string]$Task.startDate -or
            [string]$candidate.endDate -ne [string]$Task.endDate
        ) {
            continue
        }
        if ((Get-DeliveryKey -Task $candidate) -ne $deliveryKey) {
            continue
        }
        if ($candidate.status -eq "completed" -and
            (Test-ConfirmedTask -Task $candidate)) {
            return $candidate
        }
        if (
            $candidate.status -eq "no_data" -and
            $candidate.executor -in @("tk-bill", "tk-bill-cn")
        ) {
            continue
        }
        if ($candidate.status -in @("no_data", "manual_required")) {
            return $candidate
        }
    }
    return $null
}

function Find-TaskIndex {
    param([object]$Plan, [string]$TaskId)
    for ($index = 0; $index -lt @($Plan.tasks).Count; $index++) {
        if ([string]::Equals(
            [string]$Plan.tasks[$index].taskId,
            $TaskId,
            [System.StringComparison]::Ordinal
        )) {
            return $index
        }
    }
    return -1
}

function Merge-WorkerResult {
    param(
        [object]$Plan,
        [object]$WorkerRecord,
        [int]$ExitCode
    )
    $index = Find-TaskIndex -Plan $Plan -TaskId $WorkerRecord.TaskId
    if ($index -lt 0) {
        throw "Worker result task was not found in the master plan: $($WorkerRecord.TaskId)"
    }
    $masterTask = $Plan.tasks[$index]
    $resultTask = $null
    $workerResultMatchesRun = $false
    if (Test-Path -LiteralPath $WorkerRecord.TasksPath -PathType Leaf) {
        try {
            $workerPlan = Read-JsonUtf8 -Path $WorkerRecord.TasksPath
            $resultTask = @($workerPlan.tasks | Where-Object {
                $_.taskId -eq $WorkerRecord.TaskId
            }) | Select-Object -First 1
            $workerResultMatchesRun = (
                (Get-PlanRunFingerprint -Plan $workerPlan) -eq
                    (Get-PlanRunFingerprint -Plan $Plan) -and
                $null -ne $resultTask -and
                [string]$resultTask.startDate -eq [string]$masterTask.startDate -and
                [string]$resultTask.endDate -eq [string]$masterTask.endDate
            )
        } catch {
            $resultTask = $null
            $workerResultMatchesRun = $false
        }
    }
    if (-not $workerResultMatchesRun) {
        $resultTask = $masterTask
        $resultTask.status = "failed"
        $resultTask.statusText = [System.Text.Encoding]::UTF8.GetString(
            [System.Convert]::FromBase64String("5pyq5a6M5oiQ")
        )
        $resultTask.terminalCode = "PARALLEL_WORKER_RESULT_RUN_MISMATCH"
        $resultTask.message = (
            "A worker result from a different workbook, output root, or date " +
            "range was rejected."
        )
        Set-ObjectProperty `
            -Object $resultTask `
            -Name "updatedAt" `
            -Value ([datetime]::UtcNow.ToString("o"))
    }
    if ($resultTask.status -in @("pending", "running", $null, "")) {
        $resultTask.status = "failed"
        $resultTask.statusText = [System.Text.Encoding]::UTF8.GetString(
            [System.Convert]::FromBase64String("5pyq5a6M5oiQ")
        )
        $resultTask.terminalCode = "PARALLEL_WORKER_EXIT_$ExitCode"
        $resultTask.message = (
            "Parallel worker exited without a terminal task result. " +
            "See worker logs: $($WorkerRecord.StdoutPath)"
        )
        Set-ObjectProperty `
            -Object $resultTask `
            -Name "updatedAt" `
            -Value ([datetime]::UtcNow.ToString("o"))
    }
    $Plan.tasks[$index] = $resultTask
    return $resultTask
}

function Save-CoordinatorState {
    param(
        [string]$Path,
        [hashtable]$Running,
        [string]$RunFingerprint
    )
    $workers = @(
        $Running.Values | ForEach-Object {
            [ordered]@{
                taskId = $_.TaskId
                storeName = $_.StoreName
                storeKey = $_.StoreKey
                pid = $_.Process.Id
                processStartTimeUtc = $_.ProcessStartTimeUtc
                tasksPath = $_.TasksPath
                stdoutPath = $_.StdoutPath
                stderrPath = $_.StderrPath
                workerRoot = $_.WorkerRoot
                runFingerprint = $_.RunFingerprint
                startedAt = $_.StartedAt
            }
        }
    )
    Save-JsonAtomic -Path $Path -Value ([ordered]@{
        schemaVersion = 2
        runFingerprint = $RunFingerprint
        updatedAt = [datetime]::UtcNow.ToString("o")
        workers = $workers
    })
}

$TasksPath = (Resolve-Path -LiteralPath $TasksPath).Path
$SerialRun = (Resolve-Path -LiteralPath $SerialRun).Path
$WorkerRun = (Resolve-Path -LiteralPath $WorkerRun).Path
$runRoot = Split-Path -Parent $TasksPath
$coordinatorLockPath = Join-Path $runRoot "parallel-coordinator.lock"
try {
    $coordinatorLockStream = [System.IO.File]::Open(
        $coordinatorLockPath,
        [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None
    )
} catch {
    throw (
        "Another parallel coordinator is already using this task plan. " +
        "Wait for it to finish before starting a second coordinator."
    )
}
$plan = Read-JsonUtf8 -Path $TasksPath
$plan = Upgrade-MonthlyTaskPlan -Plan $plan -Path $TasksPath
$planRunFingerprint = Get-PlanRunFingerprint -Plan $plan
$parallelRoot = Join-Path (
    (Join-Path $runRoot "parallel-workers")
) $planRunFingerprint
$sessionRoot = Join-Path $runRoot "parallel-sessions"
$statePath = Join-Path $runRoot "parallel-state.json"
$lifecycleLock = Join-Path $runRoot "feikua-lifecycle.lock"
$startupBarrierRoot = Join-Path (
    (Join-Path $runRoot "parallel-startup-barrier")
) $planRunFingerprint
$startupBarrierTargetPath = Join-Path $startupBarrierRoot "target.txt"
$startupBarrierReleasePath = Join-Path $startupBarrierRoot "released"
New-Item -ItemType Directory -Path $parallelRoot -Force | Out-Null
New-Item -ItemType Directory -Path $sessionRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $lifecycleLock -PathType Leaf)) {
    [System.IO.File]::WriteAllBytes($lifecycleLock, [byte[]](0))
}

if (-not $AllowLowMemory -and $MinimumFreeMemoryGB -gt 0) {
    try {
        $freeGB = Get-FreePhysicalMemoryGB
        if ($null -eq $freeGB) {
            throw "Free-memory query returned no result."
        }
        Write-Host "PARALLEL_FREE_MEMORY_GB: $freeGB"
        if ($freeGB -lt $MinimumFreeMemoryGB) {
            throw (
                "Only $freeGB GB physical memory is free; " +
                "$MinimumFreeMemoryGB GB is required for concurrency $MaxConcurrency. " +
                "Close other applications or pass -AllowLowMemory explicitly."
            )
        }
    } catch {
        if ($_.Exception.Message -like "Only * physical memory is free*") {
            throw
        }
        Write-Host "PARALLEL_MEMORY_CHECK_UNAVAILABLE: $($_.Exception.Message)"
    }
}

$running = @{}
if (Test-Path -LiteralPath $statePath -PathType Leaf) {
    try {
        $previousState = Read-JsonUtf8 -Path $statePath
        $stateMatchesRun = (
            [string]$previousState.runFingerprint -eq $planRunFingerprint
        )
        if (-not $stateMatchesRun) {
            $incompatibleLiveWorkers = @(
                $previousState.workers | Where-Object {
                    $candidateProcess = Get-Process `
                        -Id ([int]$_.pid) `
                        -ErrorAction SilentlyContinue
                    return (
                        Test-ProcessMatchesStateEntry `
                            -Process $candidateProcess `
                            -Entry $_ `
                            -ExpectedWorkerRun $WorkerRun
                    )
                }
            )
            if ($incompatibleLiveWorkers.Count -gt 0) {
                throw (
                    "A previous parallel run for another workbook, output root, " +
                    "or date range still has live workers. Stop that run before " +
                    "starting this one."
                )
            }
            Write-Host "PARALLEL_INCOMPATIBLE_STALE_STATE_IGNORED"
        } else {
            foreach ($entry in @($previousState.workers)) {
                $process = Get-Process -Id ([int]$entry.pid) -ErrorAction SilentlyContinue
                if (
                    -not (
                        Test-ProcessMatchesStateEntry `
                            -Process $process `
                            -Entry $entry `
                            -ExpectedWorkerRun $WorkerRun
                    )
                ) {
                    $process = $null
                }
                $record = [PSCustomObject]@{
                    TaskId = [string]$entry.taskId
                    StoreName = [string]$entry.storeName
                    StoreKey = [string]$entry.storeKey
                    Process = $process
                    ProcessStartTimeUtc = [string]$entry.processStartTimeUtc
                    TasksPath = [string]$entry.tasksPath
                    StdoutPath = [string]$entry.stdoutPath
                    StderrPath = [string]$entry.stderrPath
                    WorkerRoot = [string]$entry.workerRoot
                    RunFingerprint = $planRunFingerprint
                    StartedAt = [string]$entry.startedAt
                }
                if ($null -ne $process) {
                    $running[$record.TaskId] = $record
                    Write-Host "PARALLEL_WORKER_REATTACHED: $($record.StoreName) pid=$($process.Id)"
                } else {
                    $merged = Merge-WorkerResult -Plan $plan -WorkerRecord $record -ExitCode 1
                    Write-Host "PARALLEL_STALE_WORKER_MERGED: $($merged.storeName) status=$($merged.status)"
                }
            }
            Save-JsonAtomic -Path $TasksPath -Value $plan
        }
    } catch {
        throw "Could not recover parallel worker state: $($_.Exception.Message)"
    }
}
Save-CoordinatorState `
    -Path $statePath `
    -Running $running `
    -RunFingerprint $planRunFingerprint

$statusFilter = @()
if ($OnlyStatuses) {
    $statusFilter = @(
        $OnlyStatuses.Split(",") |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ }
    )
}
$selected = @($plan.tasks | Where-Object {
    (
        $OnlyPlatform -eq "all" -or
        $_.platformKey -eq $OnlyPlatform -or
        ($OnlyPlatform -eq "tiktok-all" -and
            $_.platformKey -in @("tiktok", "tiktok-global"))
    ) -and
    (
        -not $OfficialWarehouseOnly -or
        (
            $_.platformKey -eq "shopee" -and
            $_.officialWarehouseOrders -eq $true
        )
    ) -and
    (-not $OnlySheet -or $_.sheet -ceq $OnlySheet) -and
    (-not $OnlyStore -or $_.storeName -ceq $OnlyStore) -and
    (-not $OnlyTaskId -or $_.taskId -ceq $OnlyTaskId) -and
    ($statusFilter.Count -eq 0 -or $_.status -in $statusFilter) -and
    (
        -not $AdCostsOnlyRecovery -or
        (
            $_.platformKey -in @("tiktok", "tiktok-global") -and
            $_.incomeStatus -in @("completed", "no_data") -and
            $_.adCostStatus -notin @("completed", "no_data") -and
            $_.status -notin @("completed", "no_data")
        )
    ) -and
    (
        -not $RecoveryPass -or
        (Test-RecoverableTask `
            -Task $_ `
            -MaximumAttempts $MaxRecoveryAttempts)
    )
})
if ($IgnoreExistingRecoveryBackoff) {
    foreach ($selectedTask in $selected) {
        Set-ObjectProperty `
            -Object $selectedTask `
            -Name "nextRecoveryAt" `
            -Value $null
    }
    Save-JsonAtomic -Path $TasksPath -Value $plan
    Write-Host "PARALLEL_EXISTING_RECOVERY_BACKOFF_CLEARED"
}
if ($OnlyTaskId -and $selected.Count -ne 1) {
    throw "The exact task ID filter must match one task; matched $($selected.Count): $OnlyTaskId"
}
if ($OnlyStore -and -not $OnlyTaskId -and $selected.Count -ne 1) {
    throw "The exact store filter must match one task; matched $($selected.Count): $OnlyStore"
}
if ($OnlySheet -and $selected.Count -eq 0) {
    throw "The worksheet filter did not match any task: $OnlySheet"
}
if ($MaxTasks -gt 0 -and $selected.Count -gt $MaxTasks) {
    # Keep workbook/task-plan order deterministic. The coordinator remains the
    # only writer of the master task file while executing a bounded live pilot.
    $selected = @($selected | Select-Object -First $MaxTasks)
    Write-Host "PARALLEL_TASK_LIMIT_APPLIED: selected=$($selected.Count)"
}

$preservedStoreOwners = @{}
foreach ($planTask in @($plan.tasks)) {
    $sessionDisposition = if (
        $null -ne $planTask.PSObject.Properties["sessionDisposition"]
    ) {
        [string]$planTask.sessionDisposition
    } else {
        ""
    }
    if (
        $planTask.terminalCode -notin @(
            "TIKTOK_EXPORT_SUBMITTED_SESSION_PRESERVED",
            "TIKTOK_AD_COST_MANUAL_VERIFICATION_SESSION_PRESERVED"
        ) -and
        $sessionDisposition -notin @(
            "preserved_recoverable",
            "preserved_manual"
        )
    ) {
        continue
    }
    $preservedKey = Get-TaskStoreKey -Task $planTask
    if (-not $preservedStoreOwners.ContainsKey($preservedKey)) {
        $preservedStoreOwners[$preservedKey] = [string]$planTask.taskId
    }
}

# A preserved Feikua window still consumes a full Chrome session even though
# it is no longer counted as a running worker. Reconcile old preserved
# sessions before scheduling replacements so active + preserved windows can
# never grow without bound across retries or coordinator restarts. Keep live
# worker stores and prefer retaining manual-takeover sessions; evict older
# recoverable sessions first.
$activeStoreKeysAtStart = @(
    $running.Values |
        ForEach-Object { [string]$_.StoreKey } |
        Select-Object -Unique
)
$preservedEvictionCandidates = [System.Collections.Generic.List[object]]::new()
foreach ($preservedKey in @($preservedStoreOwners.Keys)) {
    if ($preservedKey -in $activeStoreKeysAtStart) {
        continue
    }
    $ownerTaskId = [string]$preservedStoreOwners[$preservedKey]
    $ownerIndex = Find-TaskIndex -Plan $plan -TaskId $ownerTaskId
    if ($ownerIndex -lt 0) {
        continue
    }
    $ownerTask = $plan.tasks[$ownerIndex]
    $disposition = if (
        $null -ne $ownerTask.PSObject.Properties["sessionDisposition"]
    ) {
        [string]$ownerTask.sessionDisposition
    } else {
        ""
    }
    $updatedAt = [datetime]::MinValue
    if (
        $null -ne $ownerTask.PSObject.Properties["updatedAt"] -and
        $ownerTask.updatedAt
    ) {
        try {
            $updatedAt = [datetime]::Parse(
                [string]$ownerTask.updatedAt,
                [System.Globalization.CultureInfo]::InvariantCulture,
                [System.Globalization.DateTimeStyles]::RoundtripKind
            ).ToUniversalTime()
        } catch {
            $updatedAt = [datetime]::MinValue
        }
    }
    $preservedEvictionCandidates.Add([pscustomobject]@{
        StoreKey = [string]$preservedKey
        TaskId = $ownerTaskId
        Task = $ownerTask
        KeepPriority = if ($disposition -eq "preserved_manual") { 1 } else { 0 }
        UpdatedAt = $updatedAt
    })
}
$preservedCapChanged = $false
foreach ($candidate in @(
    $preservedEvictionCandidates |
        Sort-Object KeepPriority, UpdatedAt
)) {
    $liveKeysAtStart = @(
        $activeStoreKeysAtStart +
        @($preservedStoreOwners.Keys) |
            Select-Object -Unique
    )
    if ($liveKeysAtStart.Count -le $EffectiveLiveStoreSessions) {
        break
    }
    $closed = Stop-RecoveryExhaustedStoreSession `
        -Task $candidate.Task `
        -SerialRunner $SerialRun
    if (-not $closed) {
        Write-Host (
            "PARALLEL_PRESERVED_SESSION_CAP_EVICTION_FAILED: " +
            "$($candidate.Task.storeName)"
        )
        continue
    }
    Set-ObjectProperty `
        -Object $candidate.Task `
        -Name "sessionDisposition" `
        -Value $null
    Set-ObjectProperty `
        -Object $candidate.Task `
        -Name "updatedAt" `
        -Value ([datetime]::UtcNow.ToString("o"))
    [void]$preservedStoreOwners.Remove($candidate.StoreKey)
    $preservedCapChanged = $true
    Write-Host (
        "PARALLEL_PRESERVED_SESSION_CAP_EVICTED: " +
        "$($candidate.Task.storeName)"
    )
}
if ($preservedCapChanged) {
    Save-JsonAtomic -Path $TasksPath -Value $plan
}
$liveKeysAfterReconciliation = @(
    $activeStoreKeysAtStart +
    @($preservedStoreOwners.Keys) |
        Select-Object -Unique
)
if ($liveKeysAfterReconciliation.Count -gt $EffectiveLiveStoreSessions) {
    Write-Host (
        "PARALLEL_LIVE_SESSION_CAP_BLOCKED: live={0} cap={1}; " +
        "new store sessions remain paused" -f
        $liveKeysAfterReconciliation.Count,
        $EffectiveLiveStoreSessions
    )
}

$pending = [System.Collections.ArrayList]::new()
foreach ($task in $selected) {
    if ($running.ContainsKey([string]$task.taskId)) {
        continue
    }
    if (Test-ConfirmedTask -Task $task) {
        Write-Host "Skipping confirmed task: $($task.storeName)"
        continue
    }
    [void]$pending.Add($task)
}

$startupBarrierTarget = 0
if ($WaitForInitialWave -and $running.Count -eq 0) {
    if (Test-Path -LiteralPath $startupBarrierRoot) {
        Remove-Item -LiteralPath $startupBarrierRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Path $startupBarrierRoot -Force | Out-Null
    $eligibleInitialStoreKeys = @(
        $pending | Where-Object {
            $candidateKey = Get-TaskStoreKey -Task $_
            -not $preservedStoreOwners.ContainsKey($candidateKey) -or
            [string]$preservedStoreOwners[$candidateKey] -eq [string]$_.taskId
        } | ForEach-Object {
            Get-TaskStoreKey -Task $_
        } | Select-Object -Unique
    )
    $availableInitialSessionSlots = [math]::Max(
        0,
        $EffectiveLiveStoreSessions - $preservedStoreOwners.Count
    )
    $startupBarrierTarget = [math]::Min(
        $MaxConcurrency,
        [math]::Min(
            $availableInitialSessionSlots,
            $eligibleInitialStoreKeys.Count
        )
    )
    [System.IO.File]::WriteAllText(
        $startupBarrierTargetPath,
        [string]$startupBarrierTarget,
        [System.Text.UTF8Encoding]::new($false)
    )
} elseif ($WaitForInitialWave -and (
    (Test-Path -LiteralPath $startupBarrierTargetPath -PathType Leaf) -and
    -not (Test-Path -LiteralPath $startupBarrierReleasePath -PathType Leaf)
)) {
    $startupBarrierTarget = [int][System.IO.File]::ReadAllText(
        $startupBarrierTargetPath,
        [System.Text.Encoding]::UTF8
    )
}

Write-Host "PARALLEL_EXECUTION_START"
Write-Host "max_concurrency: $MaxConcurrency"
Write-Host "configured_max_live_store_sessions: $MaxLiveStoreSessions"
Write-Host "effective_max_live_store_sessions: $EffectiveLiveStoreSessions"
Write-Host "selected_tasks: $($selected.Count)"
Write-Host "queued_tasks: $($pending.Count)"
Write-Host "reattached_workers: $($running.Count)"
Write-Host "startup_barrier_target: $startupBarrierTarget"

$lastLaunch = [datetime]::MinValue
$lastProgress = [datetime]::MinValue
$lastMemoryGateNotice = [datetime]::MinValue
$capacityEvictionFailures = @{}
$deferredTaskCount = 0

while ($pending.Count -gt 0 -or $running.Count -gt 0) {
    $finishedIds = [System.Collections.Generic.List[string]]::new()
    foreach ($taskId in @($running.Keys)) {
        $record = $running[$taskId]
        $record.Process.Refresh()
        if (-not $record.Process.HasExited) {
            $latestActivityUtc = [datetime]::MinValue
            foreach ($activityPath in @(
                [string]$record.StdoutPath,
                [string]$record.StderrPath
            )) {
                if (Test-Path -LiteralPath $activityPath -PathType Leaf) {
                    $activityUtc = (
                        Get-Item -LiteralPath $activityPath
                    ).LastWriteTimeUtc
                    if ($activityUtc -gt $latestActivityUtc) {
                        $latestActivityUtc = $activityUtc
                    }
                }
            }
            if ($latestActivityUtc -eq [datetime]::MinValue) {
                $latestActivityUtc = [datetime]::Parse(
                    [string]$record.StartedAt
                ).ToUniversalTime()
            }
            $silentMinutes = (
                [datetime]::UtcNow - $latestActivityUtc
            ).TotalMinutes
            if ($silentMinutes -ge $WorkerSilenceTimeoutMinutes) {
                Set-ObjectProperty `
                    -Object $record `
                    -Name "StallDetected" `
                    -Value $true
                Write-Host (
                    (
                        "PARALLEL_WORKER_STALL_RECOVERY: {0} " +
                        "silent={1:N1}m timeout={2}m"
                    ) -f
                        $record.StoreName,
                        $silentMinutes,
                        $WorkerSilenceTimeoutMinutes
                )
                Stop-StalledWorkerProcessTree `
                    -RootProcessId ([int]$record.Process.Id)
                Start-Sleep -Milliseconds 500
                $record.Process.Refresh()
            }
        }
        if (-not $record.Process.HasExited) {
            continue
        }
        $record.Process.WaitForExit()
        $exitCode = try {
            $rawExitCode = $record.Process.ExitCode
            if ($null -eq $rawExitCode) {
                -1
            } else {
                [int]$rawExitCode
            }
        } catch {
            -1
        }
        $result = Merge-WorkerResult `
            -Plan $plan `
            -WorkerRecord $record `
            -ExitCode $exitCode
        if (
            $null -ne $record.PSObject.Properties["StallDetected"] -and
            $record.StallDetected
        ) {
            $result.status = "failed"
            $result.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("5pyq5a6M5oiQ")
            )
            $result.terminalCode = "PARALLEL_WORKER_STALLED"
            $result.message = (
                "The worker produced no progress for " +
                "$WorkerSilenceTimeoutMinutes minutes. Its executor and " +
                "driver services were recycled while the isolated Feikua " +
                "browser was left outside the killed process tree."
            )
            Set-ObjectProperty `
                -Object $result `
                -Name "sessionDisposition" `
                -Value "preserved_recoverable"
            Set-ObjectProperty `
                -Object $result `
                -Name "updatedAt" `
                -Value ([datetime]::UtcNow.ToString("o"))
            $plan.tasks[
                (Find-TaskIndex -Plan $plan -TaskId $result.taskId)
            ] = $result
        }
        $resultDisposition = if (
            $null -ne $result.PSObject.Properties["sessionDisposition"]
        ) {
            [string]$result.sessionDisposition
        } else {
            ""
        }
        Save-JsonAtomic -Path $TasksPath -Value $plan
        Write-Host (
            "PARALLEL_WORKER_FINISHED: {0} status={1} exit={2}" -f
            $result.storeName, $result.status, $exitCode
        )
        if (
            $result.terminalCode -in @(
                "TIKTOK_EXPORT_SUBMITTED_SESSION_PRESERVED",
                "TIKTOK_AD_COST_MANUAL_VERIFICATION_SESSION_PRESERVED"
            ) -or
            $resultDisposition -in @(
                "preserved_recoverable",
                "preserved_manual"
            )
        ) {
            $preservedStoreOwners[$record.StoreKey] = [string]$result.taskId
            Write-Host (
                "PARALLEL_STORE_KEY_RESERVED_FOR_PRESERVED_SESSION: " +
                "$($record.StoreName)"
            )
        } elseif (
            $preservedStoreOwners.ContainsKey($record.StoreKey) -and
            [string]$preservedStoreOwners[$record.StoreKey] -eq
                [string]$result.taskId
        ) {
            [void]$preservedStoreOwners.Remove($record.StoreKey)
        }
        if (
            $RecoveryPass -and
            (Test-RecoverableTask `
                -Task $result `
                -MaximumAttempts $MaxRecoveryAttempts)
        ) {
            $attempt = if (
                $null -ne $result.PSObject.Properties["recoveryAttempts"]
            ) {
                [int]$result.recoveryAttempts
            } else {
                0
            }
            $backoff = Get-RecoveryBackoffSeconds -Attempt $attempt
            Set-ObjectProperty `
                -Object $result `
                -Name "lastRecoveryTerminalCode" `
                -Value ([string]$result.terminalCode)
            Set-ObjectProperty `
                -Object $result `
                -Name "lastRecoveryMessage" `
                -Value ([string]$result.message)
            Set-ObjectProperty `
                -Object $result `
                -Name "nextRecoveryAt" `
                -Value ([datetime]::UtcNow.AddSeconds($backoff).ToString("o"))
            [void]$pending.Add($result)
            Write-Host (
                (
                    "PARALLEL_RECOVERY_REQUEUED: {0} attempt={1}/{2} " +
                    "backoff={3}s"
                ) -f
                    $result.storeName,
                    $attempt,
                    $MaxRecoveryAttempts,
                    $backoff
            )
        } elseif (
            $RecoveryPass -and
            $result.status -eq "failed" -and
            $result.terminalCode -notin @(
                "TIKTOK_AD_LOGIN_OTP_REQUIRED",
                "TIKTOK_AD_LOGIN_CREDENTIALS_REJECTED",
                "TIKTOK_SELLER_HOME_NOT_REACHED",
                "SHOPEE_SELLER_HOME_NOT_REACHED",
                "LAZADA_SELLER_HOME_NOT_REACHED",
                "STOPPED_AFTER_LOGIN_TIMEOUT"
            )
        ) {
            $attempt = if (
                $null -ne $result.PSObject.Properties["recoveryAttempts"]
            ) {
                [int]$result.recoveryAttempts
            } else {
                0
            }
            if ($attempt -ge $MaxRecoveryAttempts) {
                $lastCode = [string]$result.terminalCode
                $lastMessage = [string]$result.message
                $result.status = "manual_required"
                $result.statusText = [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String("5b6F5Lq65bel")
                )
                $result.terminalCode = (
                    "HIGH_AVAILABILITY_RECOVERY_EXHAUSTED"
                )
                $result.message = (
                    "Automatic recovery was exhausted after $attempt " +
                    "attempts. Last result: $lastCode $lastMessage"
                )
                if (
                    $resultDisposition -in @(
                        "preserved_recoverable",
                        "preserved_manual"
                    ) -or
                    $preservedStoreOwners.ContainsKey($record.StoreKey)
                ) {
                    $closed = Stop-RecoveryExhaustedStoreSession `
                        -Task $result `
                        -SerialRunner $SerialRun
                    if ($closed) {
                        Set-ObjectProperty `
                            -Object $result `
                            -Name "sessionDisposition" `
                            -Value $null
                        [void]$preservedStoreOwners.Remove($record.StoreKey)
                    } else {
                        Set-ObjectProperty `
                            -Object $result `
                            -Name "sessionDisposition" `
                            -Value "preserved_manual"
                    }
                }
                Set-ObjectProperty `
                    -Object $result `
                    -Name "updatedAt" `
                    -Value ([datetime]::UtcNow.ToString("o"))
                $plan.tasks[
                    (Find-TaskIndex -Plan $plan -TaskId $result.taskId)
                ] = $result
                Save-JsonAtomic -Path $TasksPath -Value $plan
                Write-Host (
                    "PARALLEL_RECOVERY_EXHAUSTED: {0} attempts={1}" -f
                    $result.storeName, $attempt
                )
            }
        }
        $finishedIds.Add($taskId)
    }
    foreach ($taskId in $finishedIds) {
        $running.Remove($taskId)
    }
    if ($finishedIds.Count -gt 0) {
        Save-CoordinatorState `
            -Path $statePath `
            -Running $running `
            -RunFingerprint $planRunFingerprint
    }

    $launched = $false
    while ($running.Count -lt $MaxConcurrency -and $pending.Count -gt 0) {
        $activeStoreKeys = @($running.Values | ForEach-Object { $_.StoreKey })
        $liveStoreKeys = @(
            $activeStoreKeys +
            @($preservedStoreOwners.Keys) |
                Select-Object -Unique
        )
        $eligibleIndex = -1
        $eligibleWouldOpenNewSession = $false
        # Reuse a preserved exact store before opening another Chrome session.
        # This finishes checkpoints faster and releases scarce live-window
        # capacity instead of growing the browser working set.
        foreach ($preferPreserved in @($true, $false)) {
            for ($index = 0; $index -lt $pending.Count; $index++) {
                $nextRecoveryAt = [datetime]::MinValue
                if (
                    $null -ne $pending[$index].PSObject.Properties[
                        "nextRecoveryAt"
                    ] -and
                    $pending[$index].nextRecoveryAt
                ) {
                    try {
                        $nextRecoveryAt = [datetime]::Parse(
                            [string]$pending[$index].nextRecoveryAt,
                            [System.Globalization.CultureInfo]::InvariantCulture,
                            [System.Globalization.DateTimeStyles]::RoundtripKind
                        ).ToUniversalTime()
                    } catch {
                        $nextRecoveryAt = [datetime]::MinValue
                    }
                }
                if ($nextRecoveryAt -gt [datetime]::UtcNow) {
                    continue
                }
                $candidateKey = Get-TaskStoreKey -Task $pending[$index]
                $ownsPreservedSession = (
                    $preservedStoreOwners.ContainsKey($candidateKey) -and
                    [string]$preservedStoreOwners[$candidateKey] -eq
                        [string]$pending[$index].taskId
                )
                if ($preferPreserved -ne $ownsPreservedSession) {
                    continue
                }
                $blockedByPreservedSession = (
                    $preservedStoreOwners.ContainsKey($candidateKey) -and
                    -not $ownsPreservedSession
                )
                $wouldOpenNewSession = $candidateKey -notin $liveStoreKeys
                $blockedByLiveSessionCap = (
                    $wouldOpenNewSession -and
                    $liveStoreKeys.Count -ge $EffectiveLiveStoreSessions
                )
                if (
                    $candidateKey -notin $activeStoreKeys -and
                    -not $blockedByPreservedSession -and
                    -not $blockedByLiveSessionCap
                ) {
                    $eligibleIndex = $index
                    $eligibleWouldOpenNewSession = $wouldOpenNewSession
                    break
                }
            }
            if ($eligibleIndex -ge 0) {
                break
            }
        }
        if ($eligibleIndex -lt 0) {
            # A recoverable session in backoff must not leave a worker slot
            # empty while also consuming the effective live-window cap.
            # Close one non-running, non-manual preserved session and let a
            # different ready task fill the slot. The evicted task remains in
            # the pending queue and may reopen after its backoff expires.
            $readyNewSessionExists = $false
            for ($index = 0; $index -lt $pending.Count; $index++) {
                $candidateNextRecoveryAt = [datetime]::MinValue
                if (
                    $null -ne $pending[$index].PSObject.Properties[
                        "nextRecoveryAt"
                    ] -and
                    $pending[$index].nextRecoveryAt
                ) {
                    try {
                        $candidateNextRecoveryAt = [datetime]::Parse(
                            [string]$pending[$index].nextRecoveryAt,
                            [System.Globalization.CultureInfo]::InvariantCulture,
                            [System.Globalization.DateTimeStyles]::RoundtripKind
                        ).ToUniversalTime()
                    } catch {
                        $candidateNextRecoveryAt = [datetime]::MinValue
                    }
                }
                if ($candidateNextRecoveryAt -gt [datetime]::UtcNow) {
                    continue
                }
                $readyKey = Get-TaskStoreKey -Task $pending[$index]
                if (
                    $readyKey -notin $activeStoreKeys -and
                    $readyKey -notin $liveStoreKeys
                ) {
                    $readyNewSessionExists = $true
                    break
                }
            }
            if (
                $AllowPreservedSessionEviction -and
                $readyNewSessionExists -and
                $running.Count -lt $MaxConcurrency -and
                $liveStoreKeys.Count -ge $EffectiveLiveStoreSessions
            ) {
                $capacityCandidates = [System.Collections.Generic.List[object]]::new()
                foreach ($preservedKey in @($preservedStoreOwners.Keys)) {
                    if (
                        $preservedKey -in $activeStoreKeys -or
                        $capacityEvictionFailures.ContainsKey($preservedKey)
                    ) {
                        continue
                    }
                    $ownerTaskId = [string]$preservedStoreOwners[$preservedKey]
                    $ownerIndex = Find-TaskIndex `
                        -Plan $plan `
                        -TaskId $ownerTaskId
                    if ($ownerIndex -lt 0) {
                        continue
                    }
                    $ownerTask = $plan.tasks[$ownerIndex]
                    $ownerDisposition = if (
                        $null -ne $ownerTask.PSObject.Properties[
                            "sessionDisposition"
                        ]
                    ) {
                        [string]$ownerTask.sessionDisposition
                    } else {
                        ""
                    }
                    if ($ownerDisposition -eq "preserved_manual") {
                        continue
                    }
                    $ownerUpdatedAt = [datetime]::MinValue
                    if (
                        $null -ne $ownerTask.PSObject.Properties["updatedAt"] -and
                        $ownerTask.updatedAt
                    ) {
                        try {
                            $ownerUpdatedAt = [datetime]::Parse(
                                [string]$ownerTask.updatedAt,
                                [System.Globalization.CultureInfo]::InvariantCulture,
                                [System.Globalization.DateTimeStyles]::RoundtripKind
                            ).ToUniversalTime()
                        } catch {
                            $ownerUpdatedAt = [datetime]::MinValue
                        }
                    }
                    $capacityCandidates.Add([pscustomobject]@{
                        StoreKey = [string]$preservedKey
                        Task = $ownerTask
                        UpdatedAt = $ownerUpdatedAt
                    })
                }
                $capacityCandidate = @(
                    $capacityCandidates | Sort-Object UpdatedAt
                ) | Select-Object -First 1
                if ($null -ne $capacityCandidate) {
                    $closed = Stop-RecoveryExhaustedStoreSession `
                        -Task $capacityCandidate.Task `
                        -SerialRunner $SerialRun
                    if ($closed) {
                        Set-ObjectProperty `
                            -Object $capacityCandidate.Task `
                            -Name "sessionDisposition" `
                            -Value $null
                        Set-ObjectProperty `
                            -Object $capacityCandidate.Task `
                            -Name "updatedAt" `
                            -Value ([datetime]::UtcNow.ToString("o"))
                        [void]$preservedStoreOwners.Remove(
                            $capacityCandidate.StoreKey
                        )
                        Save-JsonAtomic -Path $TasksPath -Value $plan
                        Write-Host (
                            "PARALLEL_PRESERVED_SESSION_RELEASED_FOR_SLOT: " +
                            "$($capacityCandidate.Task.storeName)"
                        )
                        continue
                    }
                    $capacityEvictionFailures[
                        $capacityCandidate.StoreKey
                    ] = $true
                }
            }
            break
        }
        if (
            $eligibleWouldOpenNewSession -and
            -not $AllowLowMemory -and
            $MinimumLaunchFreeMemoryGB -gt 0
        ) {
            $launchFreeGB = Get-FreePhysicalMemoryGB
            if (
                $null -ne $launchFreeGB -and
                $launchFreeGB -lt $MinimumLaunchFreeMemoryGB
            ) {
                if (
                    (
                        [datetime]::UtcNow - $lastMemoryGateNotice
                    ).TotalSeconds -ge 30
                ) {
                    Write-Host (
                        "PARALLEL_MEMORY_LAUNCH_PAUSED: free={0}GB " +
                        "required={1}GB active={2}/{3}" -f
                        $launchFreeGB,
                        $MinimumLaunchFreeMemoryGB,
                        $running.Count,
                        $MaxConcurrency
                    )
                    $lastMemoryGateNotice = [datetime]::UtcNow
                }
                break
            }
        }
        $elapsed = ([datetime]::UtcNow - $lastLaunch).TotalSeconds
        if ($lastLaunch -ne [datetime]::MinValue -and
            $elapsed -lt $StartupStaggerSeconds) {
            break
        }

        $task = $pending[$eligibleIndex]
        $pending.RemoveAt($eligibleIndex)
        $taskId = [string]$task.taskId
        if (-not $taskId) {
            throw "Parallel execution requires every task to have taskId."
        }
        $masterIndex = Find-TaskIndex -Plan $plan -TaskId $taskId
        $task = $plan.tasks[$masterIndex]
        if ($RecoveryPass) {
            $recoveryAttempts = if (
                $null -ne $task.PSObject.Properties["recoveryAttempts"]
            ) {
                [int]$task.recoveryAttempts
            } else {
                0
            }
            $recoveryAttempts += 1
            Set-ObjectProperty `
                -Object $task `
                -Name "recoveryAttempts" `
                -Value $recoveryAttempts
            Set-ObjectProperty `
                -Object $task `
                -Name "lastRecoveryStartedAt" `
                -Value ([datetime]::UtcNow.ToString("o"))
            Set-ObjectProperty `
                -Object $task `
                -Name "nextRecoveryAt" `
                -Value $null
            $plan.tasks[$masterIndex] = $task
            Write-Host (
                "PARALLEL_RECOVERY_ATTEMPT_STARTED: {0} attempt={1}/{2}" -f
                $task.storeName,
                $recoveryAttempts,
                $MaxRecoveryAttempts
            )
        }
        $duplicateSource = Find-DuplicateTerminalSource -Plan $plan -Task $task
        if ($null -ne $duplicateSource) {
            $task.status = $duplicateSource.status
            $task.statusText = $duplicateSource.statusText
            $task.terminalCode = $duplicateSource.terminalCode
            $task.message = (
                "Reused terminal result from duplicate task " +
                "$($duplicateSource.taskId)."
            )
            $task.files = @($duplicateSource.files)
            foreach ($propertyName in @(
                "incomeStatus",
                "incomeTerminalCode",
                "adCostStatus",
                "adCostTerminalCode",
                "adAccountCount",
                "adAccountExports",
                "adCostSummary"
            )) {
                if ($null -ne $duplicateSource.PSObject.Properties[$propertyName]) {
                    if ($null -eq $task.PSObject.Properties[$propertyName]) {
                        $task | Add-Member `
                            -MemberType NoteProperty `
                            -Name $propertyName `
                            -Value $duplicateSource.$propertyName
                    } else {
                        $task.$propertyName = $duplicateSource.$propertyName
                    }
                }
            }
            Set-ObjectProperty `
                -Object $task `
                -Name "updatedAt" `
                -Value ([datetime]::UtcNow.ToString("o"))
            $plan.tasks[$masterIndex] = $task
            Save-JsonAtomic -Path $TasksPath -Value $plan
            Write-Host (
                "PARALLEL_DUPLICATE_RESULT_REUSED: {0} source={1} status={2}" -f
                $task.storeName, $duplicateSource.taskId, $task.status
            )
            continue
        }
        $workerRoot = Join-Path $parallelRoot $taskId
        New-Item -ItemType Directory -Path $workerRoot -Force | Out-Null
        $workerTasksPath = Join-Path $workerRoot "tasks.json"
        $workerRuntime = Join-Path $workerRoot ".runtime"
        $storeKey = Get-TaskStoreKey -Task $task
        $workerSession = Join-Path $sessionRoot (
            (Get-StoreKeyHash -StoreKey $storeKey) + ".json"
        )
        $workerPlan = (
            $plan | ConvertTo-Json -Depth 20 | ConvertFrom-Json
        )
        $workerPlan.tasks = @($workerPlan.tasks | Where-Object {
            $_.taskId -eq $taskId
        })
        $workerPlan.tasks[0].status = "running"
        $workerPlan.tasks[0].statusText = [System.Text.Encoding]::UTF8.GetString(
            [System.Convert]::FromBase64String("5omn6KGM5Lit")
        )
        $workerPlan.tasks[0].terminalCode = $null
        $workerPlan.tasks[0].message = ""
        Set-ObjectProperty `
            -Object $workerPlan.tasks[0] `
            -Name "updatedAt" `
            -Value ([datetime]::UtcNow.ToString("o"))
        Save-JsonAtomic -Path $workerTasksPath -Value $workerPlan

        $plan.tasks[$masterIndex].status = "running"
        $plan.tasks[$masterIndex].statusText = [System.Text.Encoding]::UTF8.GetString(
            [System.Convert]::FromBase64String("5omn6KGM5Lit")
        )
        $plan.tasks[$masterIndex].terminalCode = $null
        $plan.tasks[$masterIndex].message = ""
        Set-ObjectProperty `
            -Object $plan.tasks[$masterIndex] `
            -Name "updatedAt" `
            -Value ([datetime]::UtcNow.ToString("o"))
        Save-JsonAtomic -Path $TasksPath -Value $plan

        $stamp = [datetime]::UtcNow.ToString("yyyyMMddTHHmmssfff")
        $stdoutPath = Join-Path $workerRoot "$stamp.stdout.log"
        $stderrPath = Join-Path $workerRoot "$stamp.stderr.log"
        $argumentParts = @(
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", (Quote-ProcessArgument $WorkerRun),
            "-TasksPath", (Quote-ProcessArgument $workerTasksPath),
            "-SerialRun", (Quote-ProcessArgument $SerialRun),
            "-RuntimeRoot", (Quote-ProcessArgument $workerRuntime),
            "-SessionFile", (Quote-ProcessArgument $workerSession),
            "-LifecycleLock", (Quote-ProcessArgument $lifecycleLock)
        )
        if ($ResumeSubmittedShopee -and $task.executor -eq "shopp-bill") {
            if (Test-PriorShopeeSubmissionEvidence -WorkerRoot $workerRoot) {
                $argumentParts += "-ResumeSubmittedShopee"
                Write-Host "SHOPEE_TASK_RESUME_MODE: $($task.storeName)"
            } else {
                Write-Host "SHOPEE_TASK_NORMAL_SUBMIT_MODE: $($task.storeName)"
            }
        }
        if (
            $startupBarrierTarget -gt 1 -and
            -not (
                Test-Path -LiteralPath $startupBarrierReleasePath -PathType Leaf
            )
        ) {
            $argumentParts += @(
                "-StartupBarrierRoot",
                (Quote-ProcessArgument $startupBarrierRoot),
                "-StartupBarrierSize",
                $startupBarrierTarget
            )
        }
        $process = Start-Process `
            -FilePath "powershell.exe" `
            -ArgumentList ($argumentParts -join " ") `
            -WindowStyle Hidden `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -PassThru
        $record = [PSCustomObject]@{
            TaskId = $taskId
            StoreName = [string]$task.storeName
            StoreKey = $storeKey
            Process = $process
            ProcessStartTimeUtc = $process.StartTime.ToUniversalTime().ToString("o")
            TasksPath = $workerTasksPath
            StdoutPath = $stdoutPath
            StderrPath = $stderrPath
            WorkerRoot = $workerRoot
            RunFingerprint = $planRunFingerprint
            StartedAt = [datetime]::UtcNow.ToString("o")
        }
        $running[$taskId] = $record
        Save-CoordinatorState `
            -Path $statePath `
            -Running $running `
            -RunFingerprint $planRunFingerprint
        Write-Host (
            "PARALLEL_WORKER_STARTED: {0} pid={1} active={2}/{3}" -f
            $task.storeName, $process.Id, $running.Count, $MaxConcurrency
        )
        $lastLaunch = [datetime]::UtcNow
        $launched = $true
    }

    if (
        $pending.Count -eq 0 -and
        $running.Count -gt 0 -and
        $startupBarrierTarget -gt $running.Count -and
        -not (
            Test-Path -LiteralPath $startupBarrierReleasePath -PathType Leaf
        )
    ) {
        $startupBarrierTarget = $running.Count
        [System.IO.File]::WriteAllText(
            $startupBarrierTargetPath,
            [string]$startupBarrierTarget,
            [System.Text.UTF8Encoding]::new($false)
        )
        Write-Host (
            "PARALLEL_STARTUP_TARGET_REDUCED: {0}" -f
            $startupBarrierTarget
        )
    }

    if ($running.Count -eq 0 -and $pending.Count -gt 0) {
        $unreservedPending = @(
            $pending | Where-Object {
                $candidateKey = Get-TaskStoreKey -Task $_
                -not $preservedStoreOwners.ContainsKey($candidateKey) -or
                [string]$preservedStoreOwners[$candidateKey] -eq
                    [string]$_.taskId
            }
        )
        if ($unreservedPending.Count -eq 0) {
            $deferredTaskCount = $pending.Count
            Write-Host (
                "PARALLEL_PENDING_TASKS_DEFERRED_FOR_PRESERVED_SESSION: " +
                "$deferredTaskCount"
            )
            break
        }
    }

    if (([datetime]::UtcNow - $lastProgress).TotalSeconds -ge 30) {
        $activeNames = @($running.Values | ForEach-Object { $_.StoreName })
        Write-Host (
            (
                "PARALLEL_PROGRESS: active={0} queued={1} " +
                "live_sessions={2}/{3} stores=[{4}]"
            ) -f
                $running.Count,
                $pending.Count,
                @(
                    @($running.Values | ForEach-Object { $_.StoreKey }) +
                    @($preservedStoreOwners.Keys) |
                        Select-Object -Unique
                ).Count,
                $EffectiveLiveStoreSessions,
                ($activeNames -join "; ")
        )
        $lastProgress = [datetime]::UtcNow
    }

    Start-Sleep -Milliseconds $(if ($launched) { 200 } else { 500 })
}

Save-CoordinatorState `
    -Path $statePath `
    -Running $running `
    -RunFingerprint $planRunFingerprint
$coordinatorLockStream.Dispose()
Write-Host "PARALLEL_WORKERS_COMPLETE"
Write-Host "deferred_tasks: $deferredTaskCount"
