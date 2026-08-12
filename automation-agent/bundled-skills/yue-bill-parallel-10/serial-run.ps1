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
    [switch]$GeneratePreviewWorkbook,
    [switch]$SkipFinalizer,
    [switch]$FinalizeOnly,

    [string]$RuntimeRootOverride,

    [ValidateSet("all", "tiktok", "tiktok-global", "tiktok-all", "shopee", "lazada")]
    [string]$OnlyPlatform = "all",

    [string]$OnlyStore,

    [string]$OnlySheet,

    [string]$ExcludeSheet,

    [string]$OnlyStatuses,

    [switch]$SkipGrayRows,

    [string]$TkBillRoot,
    [string]$TkBillCnRoot,
    [string]$ShoppBillRoot,
    [string]$LazadaBillRoot,
    [string]$NodeExe,
    [string]$NodeModules
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding

$SkillRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$PlannerSource = Join-Path $SkillRoot "scripts\monthly_export.mjs"
if (-not $TkBillRoot) {
    $TkBillRoot = Join-Path $SkillRoot "executors\tk-bill"
}
if (-not $TkBillCnRoot) {
    $TkBillCnRoot = Join-Path $SkillRoot "executors\tk-bill-cn"
}
if (-not $ShoppBillRoot) {
    $ShoppBillRoot = Join-Path $SkillRoot "executors\shopp-bill"
}
if (-not $LazadaBillRoot) {
    $LazadaBillRoot = Join-Path $SkillRoot "executors\lazada-bill"
}

function Resolve-DateValue {
    param([string]$Value, [string]$Name)
    $parsed = [datetime]::MinValue
    if (-not [datetime]::TryParseExact(
        $Value,
        "yyyy-MM-dd",
        [System.Globalization.CultureInfo]::InvariantCulture,
        [System.Globalization.DateTimeStyles]::None,
        [ref]$parsed
    )) {
        throw "$Name must use YYYY-MM-DD."
    }
    return $parsed
}

function Resolve-NodeRuntime {
    $resolvedNode = $NodeExe
    $resolvedModules = $NodeModules
    $bundleRoot = Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\node"

    if (-not $resolvedNode) {
        $bundledNode = Join-Path $bundleRoot "bin\node.exe"
        if (Test-Path -LiteralPath $bundledNode) {
            $resolvedNode = $bundledNode
        } else {
            $nodeCommand = Get-Command node -ErrorAction SilentlyContinue
            if ($null -ne $nodeCommand) {
                $resolvedNode = $nodeCommand.Source
            }
        }
    }

    if (-not $resolvedModules) {
        $bundledModules = Join-Path $bundleRoot "node_modules"
        if (Test-Path -LiteralPath $bundledModules) {
            $resolvedModules = $bundledModules
        }
    }

    if (-not $resolvedNode -or -not (Test-Path -LiteralPath $resolvedNode)) {
        throw "Node.js was not found. Pass -NodeExe using the Codex workspace runtime."
    }
    if (-not $resolvedModules -or -not (Test-Path -LiteralPath $resolvedModules)) {
        throw "The Codex spreadsheet node_modules directory was not found. Pass -NodeModules."
    }
    return @{
        Node = (Resolve-Path -LiteralPath $resolvedNode).Path
        Modules = (Resolve-Path -LiteralPath $resolvedModules).Path
    }
}

function Save-JsonAtomic {
    param([string]$Path, [object]$Value)
    $temporary = "$Path.tmp"
    $json = $Value | ConvertTo-Json -Depth 20
    [System.IO.File]::WriteAllText($temporary, "$json`n", [System.Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Read-JsonUtf8 {
    param([string]$Path)
    $json = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
    return $json | ConvertFrom-Json
}

function Set-ObjectProperty {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Object,
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [AllowNull()]
        [object]$Value
    )
    if ($null -eq $Object.PSObject.Properties[$Name]) {
        $Object | Add-Member -MemberType NoteProperty -Name $Name -Value $Value
    } else {
        $Object.$Name = $Value
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
            $task.updatedAt = [datetime]::UtcNow.ToString("o")
        }
    }

    Set-ObjectProperty -Object $Plan -Name "schemaVersion" -Value 6
    Set-ObjectProperty -Object $Plan -Name "balanceRoot" -Value $balanceRoot
    Set-ObjectProperty -Object $Plan -Name "adCostRoot" -Value $adCostRoot
    Save-JsonAtomic -Path $Path -Value $Plan
    Write-Host "MONTHLY_TASK_PLAN_SCHEMA_UPGRADED: 6"
    return $Plan
}

function Invoke-WorkbookFinalizer {
    param([string]$TasksPath)
    $outputName = "{0}_{1}_{2}.xlsx" -f [System.IO.Path]::GetFileNameWithoutExtension($sourceWorkbook), $StartDate, $EndDate
    $outputPath = Join-Path $resolvedOutputRoot $outputName
    $resultPath = Join-Path (Split-Path -Parent $TasksPath) "finalize-result.json"
    $tasksItem = Get-Item -LiteralPath $TasksPath
    $sourceItem = Get-Item -LiteralPath $sourceWorkbook
    $cachedOutput = Get-Item -LiteralPath $outputPath -ErrorAction SilentlyContinue
    $cachedResult = Get-Item -LiteralPath $resultPath -ErrorAction SilentlyContinue
    $cacheIsCurrent = $null -ne $cachedOutput -and
        $null -ne $cachedResult -and
        $cachedOutput.Length -gt 0 -and
        $cachedOutput.LastWriteTimeUtc -ge $tasksItem.LastWriteTimeUtc -and
        $cachedResult.LastWriteTimeUtc -ge $tasksItem.LastWriteTimeUtc -and
        $cachedOutput.LastWriteTimeUtc -ge $sourceItem.LastWriteTimeUtc
    if ($cacheIsCurrent) {
        Write-Host "Reusing unchanged verified workbook: $outputPath"
        return
    }
    $finalizerArgs = @(
        $plannerRuntime,
        "--command", "finalize",
        "--tasks", $TasksPath,
        "--output-workbook", $outputPath
    )
    Write-Host "Generating the updated workbook..."
    $finalizeStarted = [datetime]::UtcNow.AddSeconds(-2)
    & $runtime.Node @finalizerArgs
    $finalizerExitCode = $LASTEXITCODE
    $inspectSidecar = "$outputPath.inspect.ndjson"
    if (Test-Path -LiteralPath $inspectSidecar -PathType Leaf) {
        Remove-Item -LiteralPath $inspectSidecar -Force
    }
    $confirmedResult = (Test-Path -LiteralPath $resultPath -PathType Leaf) -and
        (Test-Path -LiteralPath $outputPath -PathType Leaf) -and
        ((Get-Item -LiteralPath $resultPath).LastWriteTimeUtc -ge $finalizeStarted) -and
        ((Get-Item -LiteralPath $outputPath).LastWriteTimeUtc -ge $finalizeStarted) -and
        ((Get-Item -LiteralPath $outputPath).Length -gt 0)
    if ($finalizerExitCode -ne 0 -and -not $confirmedResult) {
        throw "Workbook finalization failed with exit code $finalizerExitCode."
    }
    if ($finalizerExitCode -ne 0) {
        Write-Host "Workbook output was confirmed after artifact-tool teardown code $finalizerExitCode."
    }
}

function Test-ValidExistingDeliveryFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $false
    }
    if ((Get-Item -LiteralPath $Path).Length -le 0) {
        return $false
    }
    $extension = [System.IO.Path]::GetExtension($Path)
    if ($extension -ine ".xlsx" -and $extension -ine ".zip") {
        return $true
    }
    $archive = $null
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
        if ($extension -ieq ".xlsx") {
            return $null -ne $archive.GetEntry("xl/workbook.xml")
        }
        return @(
            $archive.Entries | Where-Object {
                -not $_.FullName.EndsWith("/") -and $_.Length -gt 0
            }
        ).Count -gt 0
    } catch {
        return $false
    } finally {
        if ($null -ne $archive) {
            $archive.Dispose()
        }
    }
}

function Move-ConfirmedFile {
    param([object]$Task, [string]$SourcePath)
    $sourceFull = [System.IO.Path]::GetFullPath($SourcePath)
    $targetFull = [System.IO.Path]::GetFullPath($Task.deliveryFilePath)
    $targetRoot = [System.IO.Path]::GetFullPath($Task.outputDir)
    if (-not $targetFull.StartsWith($targetRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "The delivery file path is outside the task output directory."
    }
    if ([string]::Equals($sourceFull, $targetFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $targetFull
    }
    if (Test-Path -LiteralPath $targetFull -PathType Leaf) {
        if (-not (Test-ValidExistingDeliveryFile -Path $sourceFull)) {
            throw "The newly confirmed delivery source file is invalid: $sourceFull"
        }
        Move-Item -LiteralPath $sourceFull -Destination $targetFull -Force
        Write-Host "DELIVERY_FILE_REPLACED_FOR_CURRENT_RUN: $targetFull"
        return (Resolve-Path -LiteralPath $targetFull).Path
    }
    Move-Item -LiteralPath $sourceFull -Destination $targetFull
    return (Resolve-Path -LiteralPath $targetFull).Path
}

function Move-ConfirmedFileTo {
    param(
        [string]$SourcePath,
        [string]$TargetPath,
        [string]$AllowedRoot
    )
    $sourceFull = [System.IO.Path]::GetFullPath($SourcePath)
    $targetFull = [System.IO.Path]::GetFullPath($TargetPath)
    $targetRoot = [System.IO.Path]::GetFullPath($AllowedRoot)
    if (-not $targetFull.StartsWith($targetRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "The delivery file path is outside its allowed output directory."
    }
    if ([string]::Equals($sourceFull, $targetFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $targetFull
    }
    if (Test-Path -LiteralPath $targetFull -PathType Leaf) {
        if (-not (Test-ValidExistingDeliveryFile -Path $sourceFull)) {
            throw "The newly confirmed delivery source file is invalid: $sourceFull"
        }
        Move-Item -LiteralPath $sourceFull -Destination $targetFull -Force
        Write-Host "DELIVERY_FILE_REPLACED_FOR_CURRENT_RUN: $targetFull"
        return (Resolve-Path -LiteralPath $targetFull).Path
    }
    Move-Item -LiteralPath $sourceFull -Destination $targetFull
    return (Resolve-Path -LiteralPath $targetFull).Path
}

function Add-ShopeeOfficialWarehouseOrder {
    param(
        [object]$Task,
        [object]$Result,
        [string[]]$ExistingFiles
    )
    if ($Task.officialWarehouseOrders -ne $true) {
        return @($ExistingFiles)
    }
    $sourcePaths = @()
    if ($null -ne $Result -and $Result.order_paths) {
        $sourcePaths = @($Result.order_paths | Where-Object { $_ })
    } elseif ($null -ne $Result -and $Result.order_path) {
        $sourcePaths = @([string]$Result.order_path)
    }
    if (
        $null -eq $Result -or
        $sourcePaths.Count -eq 0
    ) {
        throw "The required official-warehouse order report was not confirmed."
    }
    foreach ($sourcePath in $sourcePaths) {
        if (
            -not (Test-Path -LiteralPath $sourcePath -PathType Leaf) -or
            -not (Test-ValidExistingDeliveryFile -Path $sourcePath)
        ) {
            throw "A required official-warehouse order report file was not confirmed: $sourcePath"
        }
    }
    $plannedDelivery = [string]$Task.officialWarehouseDeliveryFilePath
    $plannedExtension = [System.IO.Path]::GetExtension($plannedDelivery)
    $deliveryBase = if ($plannedExtension -in @(".xlsx", ".xls", ".zip")) {
        [System.IO.Path]::Combine(
            [System.IO.Path]::GetDirectoryName($plannedDelivery),
            [System.IO.Path]::GetFileNameWithoutExtension($plannedDelivery)
        )
    } else {
        $plannedDelivery
    }
    $deliveries = @()
    for ($index = 0; $index -lt $sourcePaths.Count; $index++) {
        $sourcePath = [string]$sourcePaths[$index]
        $sourceExtension = [System.IO.Path]::GetExtension($sourcePath)
        if ($sourceExtension -notin @(".xlsx", ".xls", ".zip")) {
            throw "Shopee returned an unsupported official-warehouse order report type: $sourceExtension"
        }
        $partSuffix = if ($index -eq 0) { "" } else { "+part$($index + 1)" }
        $deliveryPath = "$deliveryBase$partSuffix$sourceExtension"
        $deliveries += Move-ConfirmedFileTo `
            -SourcePath $sourcePath `
            -TargetPath $deliveryPath `
            -AllowedRoot $Task.officialWarehouseOutputDir
    }
    Set-ObjectProperty `
        -Object $Task `
        -Name "officialWarehouseDeliveryFilePath" `
        -Value $deliveries[0]
    Set-ObjectProperty `
        -Object $Task `
        -Name "officialWarehouseDeliveryFilePaths" `
        -Value @($deliveries)
    Set-ObjectProperty -Object $Task -Name "officialWarehouseOrderStatus" -Value "completed"
    Set-ObjectProperty -Object $Task -Name "officialWarehouseOrderTerminalCode" -Value (
        "SHOPEE_OFFICIAL_WAREHOUSE_ORDER_REPORT_CONFIRMED"
    )
    return @(
        @($ExistingFiles) + @($deliveries) |
            Where-Object { $_ } |
            Select-Object -Unique
    )
}

function Invoke-ShoppBillCommand {
    param(
        [string]$Runner,
        [string]$ShopName,
        [string]$Mode,
        [string]$Start,
        [string]$End
    )
    $transcript = [System.Collections.Generic.List[string]]::new()
    $exitCode = 1
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Preserve the complete Python traceback and terminal markers. Native
        # stderr from the child PowerShell must not terminate this parent after
        # only the first "Traceback" line.
        $ErrorActionPreference = "Continue"
        & powershell -ExecutionPolicy Bypass -File $Runner $ShopName $Mode $Start $End 2>&1 |
            ForEach-Object {
                $line = "$_"
                Write-Host $line
                $transcript.Add($line)
            }
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $result = $null
    foreach ($line in $transcript) {
        if (-not $line.TrimStart().StartsWith("{")) {
            continue
        }
        try {
            $candidate = $line | ConvertFrom-Json
            if ($candidate.status -like "SHOPEE_*") {
                $result = $candidate
            }
        } catch {
            continue
        }
    }
    $joinedTranscript = ($transcript -join "`n")
    $invalidPassword = (
        $exitCode -eq 22 -and
        $transcript.Contains("SHOPEE_INVALID_PASSWORD") -and
        $transcript.Contains("SHOPEE_INVALID_PASSWORD_CONNECTION_STOPPED") -and
        $transcript.Contains("SHOPEE_MANUAL_EXPORT_REQUIRED")
    )
    $missingPassword = (
        $exitCode -eq 23 -and
        $transcript.Contains("SHOPEE_MISSING_PASSWORD") -and
        $transcript.Contains("SHOPEE_MISSING_PASSWORD_CONNECTION_STOPPED") -and
        $transcript.Contains("SHOPEE_MANUAL_EXPORT_REQUIRED")
    )
    $passwordVerificationNotCompleted = (
        $exitCode -eq 25 -and
        $joinedTranscript.Contains("SHOPEE_PASSWORD_VERIFICATION_NOT_COMPLETED") -and
        $joinedTranscript.Contains("SHOPEE_PASSWORD_VERIFICATION_NOT_COMPLETED_CONNECTION_STOPPED") -and
        $joinedTranscript.Contains("SHOPEE_MANUAL_EXPORT_REQUIRED")
    )
    $homeNotReached = (
        (
            $exitCode -eq 24 -and
            $joinedTranscript.Contains("SHOPEE_SELLER_HOME_NOT_REACHED") -and
            $joinedTranscript.Contains("SHOPEE_SELLER_HOME_NOT_REACHED_CONNECTION_STOPPED")
        ) -or (
            $exitCode -ne 0 -and
            -not $joinedTranscript.Contains("SHOPEE_SELLER_HOME_REACHED")
        )
    )
    return [pscustomobject]@{
        ExitCode = $exitCode
        Result = $result
        Transcript = $joinedTranscript
        InvalidPassword = $invalidPassword
        MissingPassword = $missingPassword
        PasswordVerificationNotCompleted = $passwordVerificationNotCompleted
        HomeNotReached = $homeNotReached
        HomeReached = $joinedTranscript.Contains("SHOPEE_SELLER_HOME_REACHED")
        ExecutionStarted = $joinedTranscript.Contains(
            "SHOPEE_EXPORT_EXECUTION_STARTED"
        )
    }
}

function Invoke-LazadaBothCommand {
    param(
        [string]$Runner,
        [string]$ShopName,
        [string]$Start,
        [string]$End,
        [string]$IncomeOutDir,
        [string]$BalanceOutDir
    )
    $transcript = [System.Collections.Generic.List[string]]::new()
    $exitCode = 1
    try {
        & powershell -ExecutionPolicy Bypass -File $Runner $ShopName --export-both `
            --start-date $Start --end-date $End `
            --income-out-dir $IncomeOutDir `
            --balance-out-dir $BalanceOutDir 2>&1 |
            ForEach-Object {
                $line = "$_"
                Write-Host $line
                $transcript.Add($line)
            }
        $exitCode = $LASTEXITCODE
    } catch {
        $line = "$_"
        Write-Host $line
        $transcript.Add($line)
    }
    $joined = $transcript -join "`n"
    $result = $null
    foreach ($line in $transcript) {
        if (-not $line.TrimStart().StartsWith("{")) {
            continue
        }
        try {
            $candidate = $line | ConvertFrom-Json
            if ($candidate.status -like "LAZADA_*") {
                $result = $candidate
            }
        } catch {
            continue
        }
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Result = $result
        Transcript = $joined
        InvalidCredentials = (
            $exitCode -eq 20 -and
            $joined.Contains("LAZADA_INVALID_CREDENTIALS_CONNECTION_STOPPED") -and
            $joined.Contains("LAZADA_MANUAL_EXPORT_REQUIRED")
        )
        AccountDeactivated = (
            $exitCode -eq 26 -and
            $joined.Contains("LAZADA_ACCOUNT_DEACTIVATED") -and
            $joined.Contains("LAZADA_ACCOUNT_DEACTIVATED_CONNECTION_STOPPED") -and
            $joined.Contains("LAZADA_MANUAL_EXPORT_REQUIRED")
        )
        VerificationProblem = (
            $exitCode -eq 21 -and
            $joined.Contains("LAZADA_POST_LOGIN_VERIFICATION_PROBLEM") -and
            $joined.Contains("LAZADA_VERIFICATION_PROBLEM_CONNECTION_STOPPED")
        )
        HomeNotReached = (
            (
                $exitCode -eq 25 -and
                $joined.Contains("LAZADA_SELLER_HOME_NOT_REACHED") -and
                $joined.Contains("LAZADA_SELLER_HOME_NOT_REACHED_CONNECTION_STOPPED")
            ) -or (
                $exitCode -ne 0 -and
                -not $joined.Contains("LAZADA_SELLER_CENTER_READY") -and
                -not $joined.Contains("LAZADA_SELLER_CENTER_ALREADY_OPEN")
            )
        )
        HomeReached = (
            $joined.Contains("LAZADA_SELLER_CENTER_READY") -or
            $joined.Contains("LAZADA_SELLER_CENTER_ALREADY_OPEN")
        )
        ExecutionStarted = $joined.Contains(
            "LAZADA_EXPORT_EXECUTION_STARTED"
        )
        NoFiles = (
            $exitCode -eq 24 -and
            $joined.Contains("LAZADA_INCOME_NO_FILE") -and
            $joined.Contains("LAZADA_BALANCE_NO_FILE") -and
            $joined.Contains("LAZADA_NO_FILES_CONNECTION_STOPPED")
        )
    }
}

function Test-ConfirmedFiles {
    param([object]$Task)
    $files = @($Task.files)
    if ($files.Count -eq 0) {
        return $false
    }
    foreach ($file in $files) {
        if (-not (Test-ValidExistingDeliveryFile -Path $file)) {
            return $false
        }
        $fileFull = [System.IO.Path]::GetFullPath($file)
        $allowedRoots = @($Task.outputDir)
        if ($Task.balanceDetailOutputDir) {
            $allowedRoots += $Task.balanceDetailOutputDir
        }
        if ($Task.officialWarehouseOutputDir) {
            $allowedRoots += $Task.officialWarehouseOutputDir
        }
        $underAllowedRoot = $false
        foreach ($allowedRoot in $allowedRoots) {
            if (Test-PathWithinRoot -Path $fileFull -Root $allowedRoot) {
                $underAllowedRoot = $true
                break
            }
        }
        if (-not $underAllowedRoot) {
            return $false
        }
    }
    $partial = @(
        Get-ChildItem -LiteralPath $Task.outputDir -Filter "*.crdownload" -File -ErrorAction SilentlyContinue
    )
    if ($Task.balanceDetailOutputDir) {
        $partial += @(
            Get-ChildItem -LiteralPath $Task.balanceDetailOutputDir -Filter "*.crdownload" -File -ErrorAction SilentlyContinue
        )
    }
    if ($Task.officialWarehouseOutputDir) {
        $partial += @(
            Get-ChildItem -LiteralPath $Task.officialWarehouseOutputDir -Filter "*.crdownload" -File -ErrorAction SilentlyContinue
        )
    }
    return @($partial).Count -eq 0
}

function Test-ShopeeBillingTerminal {
    param([object]$Task)
    $status = "$($Task.shopeeBillingStatus)".ToLowerInvariant()
    if ($status -eq "no_data") {
        return $true
    }
    if ($status -ne "completed") {
        return $false
    }
    $knownDeliveries = @(
        $Task.deliveryFilePath,
        $Task.balanceDetailDeliveryFilePath
    ) | Where-Object {
        $_ -and (Test-ValidExistingDeliveryFile -Path $_)
    }
    return $knownDeliveries.Count -gt 0
}

function Test-ShopeeCombinedTerminal {
    param([object]$Task)
    if (-not (Test-ShopeeBillingTerminal -Task $Task)) {
        return $false
    }
    if ($Task.officialWarehouseOrders -eq $true) {
        if (
            "$($Task.officialWarehouseOrderStatus)".ToLowerInvariant() -ne "completed" -or
            -not $Task.officialWarehouseDeliveryFilePath -or
            -not (Test-ValidExistingDeliveryFile -Path $Task.officialWarehouseDeliveryFilePath)
        ) {
            return $false
        }
    }
    return (Test-ConfirmedFiles -Task $Task)
}

function Test-TikTokIncomeTerminal {
    param([object]$Task)
    $incomeStatus = "$($Task.incomeStatus)".ToLowerInvariant()
    if ($incomeStatus -eq "no_data") {
        return $true
    }
    if ($incomeStatus -eq "completed") {
        return (Test-ConfirmedFiles -Task $Task)
    }
    return $false
}

function Test-TikTokAdCostTerminal {
    param([object]$Task)
    if ("$($Task.adCostStatus)".ToLowerInvariant() -ne "completed") {
        return $false
    }
    if (-not $Task.adCostOutputDir -or $null -eq $Task.adAccountCount) {
        return $false
    }
    $accountResults = @($Task.adAccountExports | Where-Object { $null -ne $_ })
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
        $filePaths = @($accountResult.filePaths | Where-Object { $_ })
        if ($filePaths.Count -eq 0 -and $accountResult.filePath) {
            $filePaths = @([string]$accountResult.filePath)
        }
        if ($filePaths.Count -eq 0) {
            return $false
        }
        foreach ($filePath in $filePaths) {
            if (
                -not (Test-PathWithinRoot -Path $filePath -Root $Task.adCostOutputDir) -or
                -not (Test-ValidExistingDeliveryFile -Path $filePath) -or
                (Test-Path -LiteralPath "$filePath.crdownload") -or
                (Test-Path -LiteralPath "$filePath.tmp")
            ) {
                return $false
            }
        }
    }
    $partialDownloads = @(
        Get-ChildItem -LiteralPath $Task.adCostOutputDir -File -ErrorAction SilentlyContinue |
            Where-Object {
                $_.Name.EndsWith(".crdownload", [System.StringComparison]::OrdinalIgnoreCase) -or
                $_.Name.EndsWith(".tmp", [System.StringComparison]::OrdinalIgnoreCase)
            }
    )
    return $partialDownloads.Count -eq 0
}

function Test-TikTokCombinedTerminal {
    param([object]$Task)
    return (
        $Task.executor -in @("tk-bill", "tk-bill-cn") -and
        (Test-TikTokIncomeTerminal -Task $Task) -and
        (Test-TikTokAdCostTerminal -Task $Task)
    )
}

function Find-TikTokAdAccountResult {
    param(
        [System.Collections.IEnumerable]$Results,
        [string]$AccountId,
        [string]$AccountName
    )
    foreach ($result in @($Results)) {
        if (
            $AccountId -and
            [string]::Equals(
                [string]$result.accountId,
                $AccountId,
                [System.StringComparison]::OrdinalIgnoreCase
            )
        ) {
            return $result
        }
        if (
            -not $AccountId -and
            $AccountName -and
            [string]::Equals(
                [string]$result.accountName,
                $AccountName,
                [System.StringComparison]::Ordinal
            )
        ) {
            return $result
        }
    }
    return $null
}

function Merge-TikTokAdCostMarkers {
    param(
        [object]$Task,
        [AllowNull()]
        [object]$Discovered,
        [object[]]$AccountResults,
        [AllowNull()]
        [object]$Phase
    )

    $priorResults = @($Task.adAccountExports | Where-Object { $null -ne $_ })
    $mergedResults = [System.Collections.ArrayList]::new()

    if ($null -ne $Discovered) {
        $discoveredAccounts = @($Discovered.accounts | Where-Object { $null -ne $_ })
        Set-ObjectProperty -Object $Task -Name "adAccountCount" -Value (
            [int]$Discovered.accountCount
        )
        if ([int]$Task.adAccountCount -ne $discoveredAccounts.Count) {
            $Task.adAccountCount = $discoveredAccounts.Count
        }
        foreach ($account in $discoveredAccounts) {
            $accountId = "$($account.accountId)".Trim()
            $accountName = "$($account.accountName)".Trim()
            $existing = Find-TikTokAdAccountResult `
                -Results $priorResults `
                -AccountId $accountId `
                -AccountName $accountName
            if ($null -eq $existing) {
                $existing = [pscustomobject]@{
                    accountId = $accountId
                    accountName = $accountName
                    startDate = $Task.startDate
                    endDate = $Task.endDate
                    status = "planned"
                    terminalCode = $null
                    filePath = $null
                    filePaths = @()
                    fileCount = 0
                    message = ""
                    updatedAt = [datetime]::UtcNow.ToString("o")
                }
            }
            [void]$mergedResults.Add($existing)
        }
    } else {
        foreach ($priorResult in $priorResults) {
            [void]$mergedResults.Add($priorResult)
        }
    }

    foreach ($marker in @($AccountResults | Where-Object { $null -ne $_ })) {
        $accountId = "$($marker.accountId)".Trim()
        $accountName = "$($marker.accountName)".Trim()
        $result = Find-TikTokAdAccountResult `
            -Results $mergedResults `
            -AccountId $accountId `
            -AccountName $accountName
        if ($null -eq $result) {
            $result = [pscustomobject]@{}
            [void]$mergedResults.Add($result)
        }

        $filePaths = @()
        foreach ($rawPath in @($marker.filePaths | Where-Object { $_ })) {
            $candidatePath = [string]$rawPath
            if (Test-Path -LiteralPath $candidatePath -PathType Leaf) {
                $candidatePath = (Resolve-Path -LiteralPath $candidatePath).Path
            }
            $filePaths += $candidatePath
        }
        if ($filePaths.Count -eq 0 -and $marker.filePath) {
            $candidatePath = [string]$marker.filePath
            if (Test-Path -LiteralPath $candidatePath -PathType Leaf) {
                $candidatePath = (Resolve-Path -LiteralPath $candidatePath).Path
            }
            $filePaths = @($candidatePath)
        }
        $primaryFile = if ($marker.filePath) {
            [string]$marker.filePath
        } elseif ($filePaths.Count -gt 0) {
            [string]$filePaths[0]
        } else {
            $null
        }
        if ($primaryFile -and (Test-Path -LiteralPath $primaryFile -PathType Leaf)) {
            $primaryFile = (Resolve-Path -LiteralPath $primaryFile).Path
        }
        if ($primaryFile -and $primaryFile -notin $filePaths) {
            $filePaths += $primaryFile
        }

        $resultStartDate = "$($marker.startDate)".Trim()
        $resultEndDate = "$($marker.endDate)".Trim()
        $status = "$($marker.status)".Trim().ToLowerInvariant()
        $terminalCode = "$($marker.terminalCode)".Trim()
        $message = "$($marker.message)".Trim()
        if (
            $resultStartDate -ne [string]$Task.startDate -or
            $resultEndDate -ne [string]$Task.endDate
        ) {
            $status = "failed"
            $terminalCode = "TIKTOK_AD_COST_DATE_MISMATCH"
            $message = (
                "The advertising account result date range does not match " +
                "the monthly task date range."
            )
        } elseif ($status -eq "completed") {
            $invalidFiles = @($filePaths | Where-Object {
                -not (Test-PathWithinRoot -Path $_ -Root $Task.adCostOutputDir) -or
                -not (Test-ValidExistingDeliveryFile -Path $_)
            })
            if ($filePaths.Count -eq 0 -or $invalidFiles.Count -gt 0) {
                $status = "failed"
                $terminalCode = "TIKTOK_AD_COST_INVALID_FILE"
                $message = (
                    "One or more advertising cost files were missing, invalid, " +
                    "or outside the advertising output directory."
                )
            }
        } elseif ($status -notin @("no_data", "failed")) {
            $status = "failed"
            $terminalCode = "TIKTOK_AD_COST_INVALID_RESULT"
            $message = "The advertising account result used an unsupported status."
        }
        Set-ObjectProperty -Object $result -Name "accountId" -Value $accountId
        Set-ObjectProperty -Object $result -Name "accountName" -Value $accountName
        Set-ObjectProperty -Object $result -Name "startDate" -Value $resultStartDate
        Set-ObjectProperty -Object $result -Name "endDate" -Value $resultEndDate
        Set-ObjectProperty -Object $result -Name "status" -Value $status
        Set-ObjectProperty -Object $result -Name "terminalCode" -Value $terminalCode
        Set-ObjectProperty -Object $result -Name "filePath" -Value $primaryFile
        Set-ObjectProperty -Object $result -Name "filePaths" -Value @($filePaths)
        Set-ObjectProperty -Object $result -Name "fileCount" -Value $filePaths.Count
        Set-ObjectProperty -Object $result -Name "message" -Value $message
        Set-ObjectProperty -Object $result -Name "updatedAt" -Value (
            [datetime]::UtcNow.ToString("o")
        )
    }

    Set-ObjectProperty -Object $Task -Name "adAccountExports" -Value @($mergedResults)
    Set-ObjectProperty -Object $Task -Name "adCostSummary" -Value $Phase
    $completedResults = @($mergedResults | Where-Object {
        "$($_.status)".ToLowerInvariant() -eq "completed"
    }).Count
    $noDataResults = @($mergedResults | Where-Object {
        "$($_.status)".ToLowerInvariant() -eq "no_data"
    }).Count
    $phaseComplete = (
        $null -ne $Phase -and
        "$($Phase.status)".ToLowerInvariant() -eq "completed" -and
        [int]$Phase.failedCount -eq 0 -and
        $null -ne $Task.adAccountCount -and
        [int]$Phase.accountCount -eq [int]$Task.adAccountCount -and
        [int]$Phase.completedCount -eq $completedResults -and
        [int]$Phase.noDataCount -eq $noDataResults -and
        ($completedResults + $noDataResults) -eq [int]$Task.adAccountCount
    )
    if ($phaseComplete) {
        Set-ObjectProperty -Object $Task -Name "adCostStatus" -Value "completed"
        Set-ObjectProperty -Object $Task -Name "adCostTerminalCode" -Value (
            "TIKTOK_AD_COST_PHASE_COMPLETE"
        )
        if (-not (Test-TikTokAdCostTerminal -Task $Task)) {
            $Task.adCostStatus = "failed"
            $Task.adCostTerminalCode = "TIKTOK_AD_COST_RESULTS_INCOMPLETE"
        }
    } else {
        Set-ObjectProperty -Object $Task -Name "adCostStatus" -Value "failed"
        Set-ObjectProperty -Object $Task -Name "adCostTerminalCode" -Value (
            "TIKTOK_AD_COST_PHASE_INCOMPLETE"
        )
    }
}

function Test-ValidXlsx {
    param([string]$Path)
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
        $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
        try {
            $entry = $archive.Entries | Where-Object {
                $_.FullName -eq "xl/workbook.xml"
            } | Select-Object -First 1
            return $null -ne $entry
        } finally {
            $archive.Dispose()
        }
    } catch {
        return $false
    }
}

function Find-RecoverableTikTokDownload {
    param([object]$Task)
    $recoverableStatus = $Task.status -eq "running" -or (
        $Task.status -eq "failed" -and
        $Task.terminalCode -eq "STOPPED_AFTER_EXPORT_TIMEOUT"
    )
    if (-not $recoverableStatus -or $Task.executor -notin @("tk-bill", "tk-bill-cn")) {
        return $null
    }
    $candidates = @(
        Get-ChildItem -LiteralPath $Task.outputDir -Filter "income_*.xlsx" -File -ErrorAction SilentlyContinue |
            Where-Object {
                -not (Test-Path -LiteralPath "$($_.FullName).crdownload")
            } |
            Sort-Object LastWriteTimeUtc -Descending
    )
    foreach ($candidate in $candidates) {
        $firstSize = $candidate.Length
        Start-Sleep -Milliseconds 250
        $refreshed = Get-Item -LiteralPath $candidate.FullName -ErrorAction SilentlyContinue
        if ($null -eq $refreshed -or $firstSize -le 0 -or $refreshed.Length -ne $firstSize) {
            continue
        }
        if (Test-ValidXlsx -Path $candidate.FullName) {
            return $candidate.FullName
        }
    }
    return $null
}

$directPlan = -not [string]::IsNullOrWhiteSpace($TasksPath)
if ($directPlan) {
    $TasksPath = (Resolve-Path -LiteralPath $TasksPath).Path
    $plan = Read-JsonUtf8 -Path $TasksPath
    $plan = Upgrade-MonthlyTaskPlan -Plan $plan -Path $TasksPath
    if (-not $plan.sourceWorkbook -or -not $plan.outputRoot -or -not $plan.startDate -or -not $plan.endDate) {
        throw "The existing task plan is missing sourceWorkbook, outputRoot, startDate, or endDate."
    }
    if ($Workbook -and -not [string]::Equals(
        [System.IO.Path]::GetFullPath($Workbook),
        [System.IO.Path]::GetFullPath($plan.sourceWorkbook),
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw "-Workbook does not match the existing task plan."
    }
    if ($StartDate -and $StartDate -ne $plan.startDate) {
        throw "-StartDate does not match the existing task plan."
    }
    if ($EndDate -and $EndDate -ne $plan.endDate) {
        throw "-EndDate does not match the existing task plan."
    }
    if ($OutputRoot -and -not [string]::Equals(
        [System.IO.Path]::GetFullPath($OutputRoot),
        [System.IO.Path]::GetFullPath($plan.outputRoot),
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw "-OutputRoot does not match the existing task plan."
    }
    $Workbook = $plan.sourceWorkbook
    $StartDate = $plan.startDate
    $EndDate = $plan.endDate
    $OutputRoot = $plan.outputRoot
} else {
    if (-not $Workbook) {
        throw "-Workbook is required unless -TasksPath is supplied."
    }
    if (-not $StartDate) {
        throw "-StartDate is required unless -TasksPath is supplied."
    }
    if (-not $EndDate) {
        throw "-EndDate is required unless -TasksPath is supplied."
    }
}

$sourceWorkbook = (Resolve-Path -LiteralPath $Workbook).Path
$start = Resolve-DateValue -Value $StartDate -Name "-StartDate"
$end = Resolve-DateValue -Value $EndDate -Name "-EndDate"
if ($end -lt $start) {
    throw "-EndDate must be on or after -StartDate."
}
if (($end - $start).TotalDays + 1 -gt 90) {
    throw "The requested date range cannot exceed 90 days."
}

if (-not $OutputRoot) {
    $parent = Split-Path -Parent $sourceWorkbook
    $OutputRoot = Join-Path $parent "monthly-bill-run_${StartDate}_${EndDate}"
}
$resolvedOutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)
New-Item -ItemType Directory -Path $resolvedOutputRoot -Force | Out-Null

$runtime = Resolve-NodeRuntime
$runtimeRoot = if ($RuntimeRootOverride) {
    [System.IO.Path]::GetFullPath($RuntimeRootOverride)
} else {
    Join-Path $resolvedOutputRoot "_run\.runtime"
}
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
$runtimeModules = Join-Path $runtimeRoot "node_modules"
if (-not (Test-Path -LiteralPath $runtimeModules)) {
    New-Item -ItemType Junction -Path $runtimeModules -Target $runtime.Modules | Out-Null
}
$plannerRuntime = Join-Path $runtimeRoot "monthly_export.mjs"
Copy-Item -LiteralPath $PlannerSource -Destination $plannerRuntime -Force

if ($directPlan) {
    $tasksPath = $TasksPath
    Write-Host "Using existing task plan: $tasksPath"
} else {
    $plannerArgs = @(
        $plannerRuntime,
        "--command", "plan",
        "--workbook", $sourceWorkbook,
        "--start-date", $StartDate,
        "--end-date", $EndDate,
        "--output-root", $resolvedOutputRoot
    )
    if ($Resume) {
        $plannerArgs += "--resume"
    }
    if ($ExcludeSheet) {
        $plannerArgs += @("--exclude-sheet", $ExcludeSheet)
    }
    if ($SkipGrayRows) {
        $plannerArgs += "--skip-gray-rows"
    }

    Write-Host "Planning monthly balance-bill tasks..."
    & $runtime.Node @plannerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Task planning failed with exit code $LASTEXITCODE."
    }

    $tasksPath = Join-Path $resolvedOutputRoot "_run\tasks.json"
    $plan = Read-JsonUtf8 -Path $tasksPath
}
$selectedTasks = @($plan.tasks | Where-Object {
    (
        $OnlyPlatform -eq "all" -or
        $_.platformKey -eq $OnlyPlatform -or
        ($OnlyPlatform -eq "tiktok-all" -and $_.platformKey -in @("tiktok", "tiktok-global"))
    ) -and
    (-not $OnlySheet -or [string]::Equals($_.sheet, $OnlySheet, [System.StringComparison]::Ordinal)) -and
    (-not $OnlyStore -or [string]::Equals($_.storeName, $OnlyStore, [System.StringComparison]::Ordinal))
})
if ($OnlyStatuses) {
    $statusFilter = @(
        $OnlyStatuses.Split(",") |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ }
    )
    $selectedTasks = @(
        $selectedTasks | Where-Object { $_.status -in $statusFilter }
    )
    Write-Host "Filtering task statuses: $($statusFilter -join ', ')"
}
if ($OnlyStore -and $selectedTasks.Count -ne 1) {
    throw "The exact store filter must match one task; matched $($selectedTasks.Count): $OnlyStore"
}
if ($OnlySheet -and $selectedTasks.Count -eq 0) {
    throw "The worksheet filter did not match any task: $OnlySheet"
}

if ($FinalizeOnly) {
    Invoke-WorkbookFinalizer -TasksPath $tasksPath
    Write-Host "MONTHLY_BILL_FINALIZE_COMPLETE"
    Write-Host "tasks_path: $tasksPath"
    exit 0
}

if (-not $Execute) {
    if ($GeneratePreviewWorkbook) {
        Invoke-WorkbookFinalizer -TasksPath $tasksPath
    }
    Write-Host "MONTHLY_BILL_DRY_RUN_COMPLETE"
    Write-Host "tasks_path: $tasksPath"
    Write-Host "balance_root: $($plan.balanceRoot)"
    Write-Host "ad_cost_root: $($plan.adCostRoot)"
    Write-Host "total_tasks: $(@($plan.tasks).Count)"
    Write-Host "selected_tasks: $($selectedTasks.Count)"
    exit 0
}

foreach ($task in $selectedTasks) {
    $tkAdCostsOnly = $false
    $shopeeOrdersOnly = $false
    if (-not $task.executorSupported) {
        $task.status = "unsupported"
        $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5pqC5LiN5pSv5oyB"))
        $task.terminalCode = "EXECUTOR_NOT_AVAILABLE"
        $task.message = $task.unsupportedReason
        $task.updatedAt = [datetime]::UtcNow.ToString("o")
        Save-JsonAtomic -Path $tasksPath -Value $plan
        continue
    }

    $confirmedTask = if ($task.executor -in @("tk-bill", "tk-bill-cn")) {
        Test-TikTokCombinedTerminal -Task $task
    } elseif ($task.executor -eq "shopp-bill") {
        Test-ShopeeCombinedTerminal -Task $task
    } else {
        Test-ConfirmedFiles -Task $task
    }
    if ($task.status -eq "completed" -and $confirmedTask) {
        Write-Host "Skipping confirmed task: $($task.storeName)"
        continue
    }

    $recoveredTikTokFile = Find-RecoverableTikTokDownload -Task $task
    if ($null -ne $recoveredTikTokFile) {
        Write-Host "Recovering confirmed TikTok download without resubmitting: $recoveredTikTokFile"
        $task.files = @(Move-ConfirmedFile -Task $task -SourcePath $recoveredTikTokFile)
        if (Test-ConfirmedFiles -Task $task) {
            if ($task.executor -in @("tk-bill", "tk-bill-cn")) {
                Set-ObjectProperty -Object $task -Name "incomeStatus" -Value "completed"
                Set-ObjectProperty -Object $task -Name "incomeTerminalCode" -Value (
                    "TIKTOK_EXPORT_FILE_RECOVERED"
                )
                $task.status = "planned"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String("5b6F5omn6KGM")
                )
                $task.terminalCode = $null
                $task.message = "Recovered the income file; advertising cost accounts remain to be processed."
                $task.updatedAt = [datetime]::UtcNow.ToString("o")
                Save-JsonAtomic -Path $tasksPath -Value $plan
            } else {
                $task.status = "completed"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5a6M5oiQ"))
                $task.terminalCode = "TIKTOK_EXPORT_FILE_RECOVERED"
                $task.message = "Recovered a stable, valid TikTok download from an interrupted executor session."
                $task.updatedAt = [datetime]::UtcNow.ToString("o")
                Save-JsonAtomic -Path $tasksPath -Value $plan
                continue
            }
        }
    }

    if (
        $task.executor -in @("tk-bill", "tk-bill-cn") -and
        (Test-TikTokIncomeTerminal -Task $task) -and
        -not (Test-TikTokAdCostTerminal -Task $task)
    ) {
        $tkAdCostsOnly = $true
        Write-Host (
            "TIKTOK_AD_COSTS_ONLY_RESUME: income is already terminal; " +
            "the original income export will not be repeated."
        )
    }
    if (
        $task.executor -eq "shopp-bill" -and
        $task.officialWarehouseOrders -eq $true -and
        (Test-ShopeeBillingTerminal -Task $task) -and
        (
            "$($task.officialWarehouseOrderStatus)".ToLowerInvariant() -ne "completed" -or
            -not $task.officialWarehouseDeliveryFilePath -or
            -not (Test-ValidExistingDeliveryFile -Path $task.officialWarehouseDeliveryFilePath)
        )
    ) {
        $shopeeOrdersOnly = $true
        Write-Host (
            "SHOPEE_OFFICIAL_WAREHOUSE_ORDERS_ONLY_RESUME: income and balance " +
            "are already terminal; they will not be repeated."
        )
    }

    $task.status = "running"
    $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5omn6KGM5Lit"))
    $task.terminalCode = $null
    $task.message = ""
    if ($task.executor -in @("tk-bill", "tk-bill-cn")) {
        Set-ObjectProperty -Object $task -Name "adCostStatus" -Value "running"
        Set-ObjectProperty -Object $task -Name "adCostTerminalCode" -Value $null
        Set-ObjectProperty -Object $task -Name "adAccountCount" -Value $null
        Set-ObjectProperty -Object $task -Name "adCostSummary" -Value $null
    }
    if ($task.executor -eq "shopp-bill") {
        if (-not $shopeeOrdersOnly) {
            Set-ObjectProperty -Object $task -Name "shopeeBillingStatus" -Value "running"
            Set-ObjectProperty -Object $task -Name "shopeeBillingTerminalCode" -Value $null
        }
        if ($task.officialWarehouseOrders -eq $true) {
            Set-ObjectProperty -Object $task -Name "officialWarehouseOrderStatus" -Value "running"
            Set-ObjectProperty -Object $task -Name "officialWarehouseOrderTerminalCode" -Value $null
        }
    }
    $task.updatedAt = [datetime]::UtcNow.ToString("o")
    Save-JsonAtomic -Path $tasksPath -Value $plan

    if ($task.executor -eq "lazada-bill") {
        $executorRun = Join-Path $LazadaBillRoot "run.ps1"
        if (-not (Test-Path -LiteralPath $executorRun)) {
            throw "lazada-bill was not found at $executorRun"
        }
        Write-Host "Running combined Lazada income and balance-detail export: $($task.storeName)"
        $bothRun = Invoke-LazadaBothCommand `
            -Runner $executorRun `
            -ShopName $task.executorName `
            -Start $StartDate `
            -End $EndDate `
            -IncomeOutDir $task.outputDir `
            -BalanceOutDir $task.balanceDetailOutputDir

        if ($bothRun.InvalidCredentials) {
            $task.status = "manual_required"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("6LSm5Y+35oiW5a+G56CB6ZSZ6K+v77yM5bey6YCA5Ye6")
            )
            $task.terminalCode = "LAZADA_INVALID_CREDENTIALS"
            $task.message = "Lazada rejected the autofilled account or password; the exact shop connection was closed without retry."
            $task.updatedAt = [datetime]::UtcNow.ToString("o")
            Save-JsonAtomic -Path $tasksPath -Value $plan
            continue
        }
        if ($bothRun.AccountDeactivated) {
            $task.status = "manual_required"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("5bqX6ZO65bey5YGc55So77yM5bey6YCA5Ye6")
            )
            $task.terminalCode = "LAZADA_ACCOUNT_DEACTIVATED"
            $task.message = "Lazada confirmed that the seller account/store is deactivated; the exact shop connection was closed."
            $task.updatedAt = [datetime]::UtcNow.ToString("o")
            Save-JsonAtomic -Path $tasksPath -Value $plan
            continue
        }
        if ($bothRun.HomeNotReached) {
            $task.status = "manual_required"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("5pyq6L+b6aaW6aG177yM6K+35qOA5p+l55m75b2V")
            )
            $task.terminalCode = "LAZADA_SELLER_HOME_NOT_REACHED"
            $task.message = "Lazada did not reach Seller Center home; the exact shop connection was closed."
            $task.updatedAt = [datetime]::UtcNow.ToString("o")
            Save-JsonAtomic -Path $tasksPath -Value $plan
            continue
        }
        if ($bothRun.HomeReached -and -not $bothRun.ExecutionStarted) {
            $task.status = "failed"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("5pyq5a6M5oiQ")
            )
            $task.terminalCode = "LAZADA_EXECUTION_NOT_STARTED_AFTER_HOME"
            $task.message = "Lazada reached Seller Center home but did not start the bill workflow; this is a script defect, not an executing task."
            $task.updatedAt = [datetime]::UtcNow.ToString("o")
            Save-JsonAtomic -Path $tasksPath -Value $plan
            continue
        }
        if ($bothRun.VerificationProblem) {
            $task.status = "manual_required"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("5Ye6546w6aqM6K+B56CB44CB55+t5L+h44CBT1RQIOaIluWFtuS7luS6jOasoemqjOivgQ==")
            )
            $task.terminalCode = "LAZADA_POST_LOGIN_VERIFICATION_PROBLEM"
            $task.message = "Lazada required secondary verification after login; the store connection was stopped."
            $task.updatedAt = [datetime]::UtcNow.ToString("o")
            Save-JsonAtomic -Path $tasksPath -Value $plan
            continue
        }
        if ($bothRun.NoFiles) {
            $task.status = "no_data"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("5peg5pWw5o2u")
            )
            $task.terminalCode = "LAZADA_NO_FILES"
            $task.message = "No income file and no balance-detail file were available for the requested date range; the store connection was stopped."
            $task.updatedAt = [datetime]::UtcNow.ToString("o")
            Save-JsonAtomic -Path $tasksPath -Value $plan
            continue
        }

        $bothResult = $bothRun.Result
        $bothConfirmed = $bothRun.ExitCode -eq 0 -and
            $null -ne $bothResult -and
            $bothResult.status -eq "LAZADA_BOTH_DOWNLOADS_COMPLETE" -and
            (Test-Path -LiteralPath $bothResult.income_path -PathType Leaf) -and
            ((Get-Item -LiteralPath $bothResult.income_path).Length -gt 0) -and
            (Test-Path -LiteralPath $bothResult.balance_path -PathType Leaf) -and
            ((Get-Item -LiteralPath $bothResult.balance_path).Length -gt 0)
        $bothNoFiles = $bothRun.ExitCode -eq 0 -and
            $null -ne $bothResult -and
            $bothResult.status -eq "SHOPEE_NO_FILES_FOR_PERIOD" -and
            $bothResult.income_no_data -eq $true -and
            $bothResult.balance_no_data -eq $true

        if ($bothConfirmed) {
            $incomeDelivery = Move-ConfirmedFileTo `
                -SourcePath $bothResult.income_path `
                -TargetPath $task.deliveryFilePath `
                -AllowedRoot $task.outputDir
            $balanceDelivery = Move-ConfirmedFileTo `
                -SourcePath $bothResult.balance_path `
                -TargetPath $task.balanceDetailDeliveryFilePath `
                -AllowedRoot $task.balanceDetailOutputDir
            $task.files = @($incomeDelivery, $balanceDelivery)
            if (Test-ConfirmedFiles -Task $task) {
                $task.status = "completed"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5a6M5oiQ"))
                $task.terminalCode = "LAZADA_BOTH_FILES_CONFIRMED"
                $task.message = ""
            } else {
                $task.status = "failed"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5pyq5a6M5oiQ"))
                $task.terminalCode = "FILE_CONFIRMATION_FAILED"
                $task.message = "One or both Lazada files failed final confirmation."
            }
        } elseif (
            $bothRun.ExitCode -eq 0 -and
            $null -ne $bothResult -and
            $bothResult.status -eq "LAZADA_PARTIAL_DOWNLOAD_COMPLETE"
        ) {
            $partialFiles = [System.Collections.Generic.List[string]]::new()
            if (
                $bothResult.income_path -and
                (Test-Path -LiteralPath $bothResult.income_path -PathType Leaf) -and
                ((Get-Item -LiteralPath $bothResult.income_path).Length -gt 0)
            ) {
                $partialFiles.Add((Move-ConfirmedFileTo `
                    -SourcePath $bothResult.income_path `
                    -TargetPath $task.deliveryFilePath `
                    -AllowedRoot $task.outputDir))
            }
            if (
                $bothResult.balance_path -and
                (Test-Path -LiteralPath $bothResult.balance_path -PathType Leaf) -and
                ((Get-Item -LiteralPath $bothResult.balance_path).Length -gt 0)
            ) {
                $partialFiles.Add((Move-ConfirmedFileTo `
                    -SourcePath $bothResult.balance_path `
                    -TargetPath $task.balanceDetailDeliveryFilePath `
                    -AllowedRoot $task.balanceDetailOutputDir))
            }
            $task.files = @($partialFiles)
            if (Test-ConfirmedFiles -Task $task) {
                $task.status = "completed"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String("5a6M5oiQ")
                )
                $task.terminalCode = "LAZADA_AVAILABLE_FILES_CONFIRMED"
                $task.message = "One Lazada section had no file; the available file was downloaded and confirmed."
            } else {
                $task.status = "failed"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String("5pyq5a6M5oiQ")
                )
                $task.terminalCode = "FILE_CONFIRMATION_FAILED"
                $task.message = "The available Lazada file failed final confirmation."
            }
        } else {
            $task.status = "failed"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5pyq5a6M5oiQ"))
            $task.terminalCode = "LAZADA_BILL_INCOMPLETE"
            $task.message = "lazada-bill did not confirm both the income and balance-detail files."
        }

        if ($task.status -eq "completed") {
            & powershell -ExecutionPolicy Bypass -File $executorRun $task.executorName --stop
        }
        $task.updatedAt = [datetime]::UtcNow.ToString("o")
        Save-JsonAtomic -Path $tasksPath -Value $plan
        continue
    }

    if ($task.executor -eq "shopp-bill") {
        $executorRun = Join-Path $ShoppBillRoot "run.ps1"
        if (-not (Test-Path -LiteralPath $executorRun)) {
            throw "shopp-bill was not found at $executorRun"
        }
        Write-Host "Running Shopee required exports: $($task.storeName)"
        $shopeeMode = if ($shopeeOrdersOnly) {
            "--export-orders"
        } elseif ($ResumeSubmittedShopee -and $task.officialWarehouseOrders -eq $true) {
            "--resume-both-official-warehouse"
        } elseif ($ResumeSubmittedShopee) {
            "--resume-both"
        } elseif ($task.officialWarehouseOrders -eq $true) {
            "--export-both-official-warehouse"
        } else {
            "--export-both"
        }
        $remarkPassword = $null
        $secretOutput = @(
            & $runtime.Node $plannerRuntime `
                "--command" "read-secret" `
                "--workbook" $sourceWorkbook `
                "--sheet" $task.sheet `
                "--row" $task.rowNumber
        )
        foreach ($secretLine in $secretOutput) {
            if ("$secretLine".StartsWith("MONTHLY_BILL_SECRET_B64:")) {
                $encodedSecret = "$secretLine".Substring(
                    "MONTHLY_BILL_SECRET_B64:".Length
                )
                $remarkPassword = [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String($encodedSecret)
                )
                break
            }
        }
        $previousRemarkPassword = [Environment]::GetEnvironmentVariable(
            "SHOPEE_IN_SITE_PASSWORD",
            "Process"
        )
        try {
            if (-not [string]::IsNullOrWhiteSpace($remarkPassword)) {
                [Environment]::SetEnvironmentVariable(
                    "SHOPEE_IN_SITE_PASSWORD",
                    $remarkPassword,
                    "Process"
                )
                Write-Host "SHOPEE_REMARK_PASSWORD_AVAILABLE"
            } else {
                [Environment]::SetEnvironmentVariable(
                    "SHOPEE_IN_SITE_PASSWORD",
                    $null,
                    "Process"
                )
            }
            $bothRun = Invoke-ShoppBillCommand `
                -Runner $executorRun `
                -ShopName $task.executorName `
                -Mode $shopeeMode `
                -Start $StartDate `
                -End $EndDate
        } finally {
            [Environment]::SetEnvironmentVariable(
                "SHOPEE_IN_SITE_PASSWORD",
                $previousRemarkPassword,
                "Process"
            )
            $remarkPassword = $null
        }
        $bothResult = $bothRun.Result
        $bothConfirmed = $bothRun.ExitCode -eq 0 -and
            $null -ne $bothResult -and
            $bothResult.status -eq "SHOPEE_BOTH_DOWNLOADS_COMPLETE" -and
            (Test-Path -LiteralPath $bothResult.income_path -PathType Leaf) -and
            ((Get-Item -LiteralPath $bothResult.income_path).Length -gt 0) -and
            (Test-Path -LiteralPath $bothResult.balance_path -PathType Leaf) -and
            ((Get-Item -LiteralPath $bothResult.balance_path).Length -gt 0)
        $bothNoFiles = $bothRun.ExitCode -eq 0 -and
            $null -ne $bothResult -and
            $bothResult.status -eq "SHOPEE_NO_FILES_FOR_PERIOD" -and
            $bothResult.income_no_data -eq $true -and
            $bothResult.balance_no_data -eq $true -and
            -not $bothResult.income_path -and
            -not $bothResult.balance_path
        $orderConfirmed = $bothRun.ExitCode -eq 0 -and
            $null -ne $bothResult -and
            $bothResult.order_path -and
            (Test-Path -LiteralPath $bothResult.order_path -PathType Leaf) -and
            (Test-ValidExistingDeliveryFile -Path $bothResult.order_path)
        $officialOrderSatisfied = (
            $task.officialWarehouseOrders -ne $true -or $orderConfirmed
        )

        if ($bothRun.InvalidPassword) {
            $task.status = "manual_required"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("5a+G56CB6ZSZ6K+v77yM5bey6YCA5Ye6")
            )
            $task.terminalCode = "SHOPEE_INVALID_PASSWORD"
            $task.message = "Shopee rejected the autofilled login or in-site password; the exact shop connection was closed."
            if (-not $shopeeOrdersOnly) {
                $task.files = @()
            }
        } elseif ($bothRun.MissingPassword) {
            $task.status = "manual_required"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("56uZ5YaF5a+G56CB5Li656m677yM5bey6YCA5Ye6")
            )
            $task.terminalCode = "SHOPEE_MISSING_PASSWORD"
            $task.message = "Shopee requested in-site password verification, but no password was autofilled; the exact shop connection was closed."
            if (-not $shopeeOrdersOnly) {
                $task.files = @()
            }
        } elseif ($bothRun.PasswordVerificationNotCompleted) {
            $task.status = "manual_required"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("56uZ5YaF5a+G56CB6aqM6K+B5pyq5a6M5oiQ77yM5bey6YCA5Ye6")
            )
            $task.terminalCode = "SHOPEE_PASSWORD_VERIFICATION_NOT_COMPLETED"
            $task.message = "Shopee kept the in-site password verification dialog open without explicitly rejecting the password; the exact shop connection was closed."
            if (-not $shopeeOrdersOnly) {
                $task.files = @()
            }
        } elseif ($bothRun.HomeNotReached) {
            $task.status = "manual_required"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("5pyq6L+b6aaW6aG177yM6K+35qOA5p+l55m75b2V")
            )
            $task.terminalCode = "SHOPEE_SELLER_HOME_NOT_REACHED"
            $task.message = "Shopee did not reach Seller Center home; the exact shop connection was closed."
            if (-not $shopeeOrdersOnly) {
                $task.files = @()
            }
        } elseif ($bothRun.HomeReached -and -not $bothRun.ExecutionStarted) {
            $task.status = "failed"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("5pyq5a6M5oiQ")
            )
            $task.terminalCode = "SHOPEE_EXECUTION_NOT_STARTED_AFTER_HOME"
            $task.message = "Shopee reached Seller Center home but did not start the bill workflow; this is a script defect, not an executing task."
            if (-not $shopeeOrdersOnly) {
                $task.files = @()
            }
        } elseif ($shopeeOrdersOnly -and $orderConfirmed) {
            $task.files = @(
                Add-ShopeeOfficialWarehouseOrder `
                    -Task $task `
                    -Result $bothResult `
                    -ExistingFiles @($task.files)
            )
            if (Test-ShopeeCombinedTerminal -Task $task) {
                $task.status = "completed"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String("5a6M5oiQ")
                )
                $task.terminalCode = "SHOPEE_OFFICIAL_WAREHOUSE_ORDER_REPORT_CONFIRMED"
                $task.message = ""
            } else {
                $task.status = "failed"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String("5pyq5a6M5oiQ")
                )
                $task.terminalCode = "FILE_CONFIRMATION_FAILED"
                $task.message = "The official-warehouse order report was downloaded but final combined confirmation failed."
            }
        } elseif ($shopeeOrdersOnly) {
            $task.status = "failed"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("5pyq5a6M5oiQ")
            )
            $task.terminalCode = "SHOPEE_OFFICIAL_WAREHOUSE_ORDER_INCOMPLETE"
            $task.message = "Shopee did not confirm the required official-warehouse order report."
        } elseif ($bothNoFiles -and $officialOrderSatisfied) {
            Set-ObjectProperty -Object $task -Name "shopeeBillingStatus" -Value "no_data"
            Set-ObjectProperty -Object $task -Name "shopeeBillingTerminalCode" -Value (
                "SHOPEE_NO_FILES_FOR_PERIOD"
            )
            if ($task.officialWarehouseOrders -eq $true) {
                $task.files = @(
                    Add-ShopeeOfficialWarehouseOrder `
                        -Task $task `
                        -Result $bothResult `
                        -ExistingFiles @()
                )
                $task.status = "completed"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String("5a6M5oiQ")
                )
                $task.terminalCode = "SHOPEE_ORDER_CONFIRMED_BILLING_NO_DATA"
                $task.message = "Income and balance had no files; the official-warehouse order report was confirmed."
            } else {
                $task.status = "no_data"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String("5peg5pWw5o2u")
                )
                $task.terminalCode = "SHOPEE_NO_FILES_FOR_PERIOD"
                $task.message = "Shopee confirmed that neither income nor balance produced a file for the requested date range."
                $task.files = @()
            }
        } elseif ($bothConfirmed -and $officialOrderSatisfied) {
            $incomeDelivery = Move-ConfirmedFileTo `
                -SourcePath $bothResult.income_path `
                -TargetPath $task.deliveryFilePath `
                -AllowedRoot $task.outputDir
            $balanceDelivery = Move-ConfirmedFileTo `
                -SourcePath $bothResult.balance_path `
                -TargetPath $task.balanceDetailDeliveryFilePath `
                -AllowedRoot $task.balanceDetailOutputDir
            Set-ObjectProperty -Object $task -Name "shopeeBillingStatus" -Value "completed"
            Set-ObjectProperty -Object $task -Name "shopeeBillingTerminalCode" -Value (
                "SHOPEE_BOTH_FILES_CONFIRMED"
            )
            $task.files = @(
                Add-ShopeeOfficialWarehouseOrder `
                    -Task $task `
                    -Result $bothResult `
                    -ExistingFiles @($incomeDelivery, $balanceDelivery)
            )
            if (Test-ShopeeCombinedTerminal -Task $task) {
                $task.status = "completed"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5a6M5oiQ"))
                $task.terminalCode = if ($task.officialWarehouseOrders -eq $true) {
                    "SHOPEE_ALL_REQUIRED_FILES_CONFIRMED"
                } else {
                    "SHOPEE_BOTH_FILES_CONFIRMED"
                }
                $task.message = ""
            } else {
                $task.status = "failed"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5pyq5a6M5oiQ"))
                $task.terminalCode = "FILE_CONFIRMATION_FAILED"
                $task.message = "One or both Shopee files were missing, empty, outside their board directory, or still downloading."
            }
        } elseif (
            $bothRun.ExitCode -eq 0 -and
            $null -ne $bothResult -and
            $bothResult.status -eq "SHOPEE_PARTIAL_DOWNLOAD_COMPLETE" -and
            $bothResult.income_no_data -eq $true -and
            $officialOrderSatisfied -and
            (Test-Path -LiteralPath $bothResult.balance_path -PathType Leaf) -and
            ((Get-Item -LiteralPath $bothResult.balance_path).Length -gt 0)
        ) {
            $balanceDelivery = Move-ConfirmedFileTo `
                -SourcePath $bothResult.balance_path `
                -TargetPath $task.balanceDetailDeliveryFilePath `
                -AllowedRoot $task.balanceDetailOutputDir
            Set-ObjectProperty -Object $task -Name "shopeeBillingStatus" -Value "completed"
            Set-ObjectProperty -Object $task -Name "shopeeBillingTerminalCode" -Value (
                "SHOPEE_BALANCE_FILE_CONFIRMED_INCOME_NO_DATA"
            )
            $task.files = @(
                Add-ShopeeOfficialWarehouseOrder `
                    -Task $task `
                    -Result $bothResult `
                    -ExistingFiles @($balanceDelivery)
            )
            if (Test-ShopeeCombinedTerminal -Task $task) {
                $task.status = "completed"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5a6M5oiQ"))
                $task.terminalCode = "SHOPEE_BALANCE_FILE_CONFIRMED_INCOME_NO_DATA"
                $task.message = "Income export was submitted but no matching history job was generated; the balance-detail file was downloaded and confirmed."
            } else {
                $task.status = "failed"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5pyq5a6M5oiQ"))
                $task.terminalCode = "FILE_CONFIRMATION_FAILED"
                $task.message = "Shopee income had no data and the available balance-detail file failed final confirmation."
            }
        } elseif (
            $bothRun.ExitCode -eq 0 -and
            $null -ne $bothResult -and
            $bothResult.status -eq "SHOPEE_PARTIAL_DOWNLOAD_COMPLETE" -and
            $bothResult.balance_no_data -eq $true -and
            $officialOrderSatisfied -and
            (Test-Path -LiteralPath $bothResult.income_path -PathType Leaf) -and
            ((Get-Item -LiteralPath $bothResult.income_path).Length -gt 0)
        ) {
            $incomeDelivery = Move-ConfirmedFileTo `
                -SourcePath $bothResult.income_path `
                -TargetPath $task.deliveryFilePath `
                -AllowedRoot $task.outputDir
            Set-ObjectProperty -Object $task -Name "shopeeBillingStatus" -Value "completed"
            Set-ObjectProperty -Object $task -Name "shopeeBillingTerminalCode" -Value (
                "SHOPEE_INCOME_FILE_CONFIRMED_BALANCE_NO_DATA"
            )
            $task.files = @(
                Add-ShopeeOfficialWarehouseOrder `
                    -Task $task `
                    -Result $bothResult `
                    -ExistingFiles @($incomeDelivery)
            )
            if (Test-ShopeeCombinedTerminal -Task $task) {
                $task.status = "completed"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5a6M5oiQ"))
                $task.terminalCode = "SHOPEE_INCOME_FILE_CONFIRMED_BALANCE_NO_DATA"
                $task.message = "The income file was downloaded and confirmed; Shopee produced no balance file for the requested date range."
            } else {
                $task.status = "failed"
                $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5pyq5a6M5oiQ"))
                $task.terminalCode = "FILE_CONFIRMATION_FAILED"
                $task.message = "Shopee balance had no data and the available income file failed final confirmation."
            }
        } else {
            $task.status = "failed"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5pyq5a6M5oiQ"))
            $task.terminalCode = "SHOPP_BILL_INCOMPLETE"
            $task.message = "shopp-bill did not confirm both the income and balance-detail files."
        }
        $task.updatedAt = [datetime]::UtcNow.ToString("o")
        Save-JsonAtomic -Path $tasksPath -Value $plan
        continue
    }

    $executorRoot = switch ($task.executor) {
        "tk-bill" { $TkBillRoot }
        "tk-bill-cn" { $TkBillCnRoot }
        default { throw "No executable runner is configured for $($task.executor)." }
    }
    $executorRun = Join-Path $executorRoot "run.ps1"
    if (-not (Test-Path -LiteralPath $executorRun)) {
        throw "$($task.executor) was not found at $executorRun"
    }

    Write-Host "Running TikTok bill export: $($task.storeName)"
    $tkArgs = @(
        "-ExecutionPolicy", "Bypass",
        "-File", $executorRun,
        $task.executorName,
        "--start-date", $StartDate,
        "--end-date", $EndDate,
        "--out", $task.outputDir
    )
    if ($task.executor -in @("tk-bill", "tk-bill-cn")) {
        if (-not $task.adCostOutputDir) {
            throw "TikTok advertising cost output directory is missing from the task plan."
        }
        $tkArgs += @("--ad-cost-out", $task.adCostOutputDir)
        if ($tkAdCostsOnly) {
            $tkArgs += "--ad-costs-only"
        }
    }
    $transcript = [System.Collections.Generic.List[string]]::new()
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # A child executor writes diagnostic details to stderr on Selenium
        # failures.  Capture those lines as task evidence; do not let Windows
        # PowerShell promote the first stderr line into a terminating parent
        # error before the task status can be persisted.
        $ErrorActionPreference = "Continue"
        & powershell @tkArgs 2>&1 | ForEach-Object {
            $line = "$_"
            Write-Host $line
            $transcript.Add($line)
        }
        $taskExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $joined = $transcript -join "`n"
    $confirmedFiles = @()
    $adCostDiscoveredMarker = $null
    $adCostAccountResultMarkers = @()
    $adCostPhaseMarker = $null

    foreach ($line in $transcript) {
        if ($line -match "^TIKTOK_EXPORT_FILE_CONFIRMED:\s*(.+)$") {
            $candidate = $Matches[1].Trim()
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                $candidatePath = (Resolve-Path -LiteralPath $candidate).Path
                $targetRoot = [System.IO.Path]::GetFullPath($task.outputDir)
                if (Test-PathWithinRoot -Path $candidatePath -Root $targetRoot) {
                    $confirmedFiles += $candidatePath
                }
            }
            continue
        }
        if ($line -match "^TIKTOK_AD_COST_ACCOUNTS_DISCOVERED_JSON:\s*(\{.*\})\s*$") {
            try {
                $adCostDiscoveredMarker = $Matches[1] | ConvertFrom-Json
            } catch {
                Write-Host "TIKTOK_AD_COST_MARKER_PARSE_FAILED: accounts discovered"
            }
            continue
        }
        if (
            $line -match (
                "^TIKTOK_AD_COST_ACCOUNT_(?:RESULT|RESUMED)_JSON:" +
                "\s*(\{.*\})\s*$"
            )
        ) {
            try {
                $adCostAccountResultMarkers += ($Matches[1] | ConvertFrom-Json)
            } catch {
                Write-Host "TIKTOK_AD_COST_MARKER_PARSE_FAILED: account result"
            }
            continue
        }
        if ($line -match "^TIKTOK_AD_COST_PHASE_COMPLETE_JSON:\s*(\{.*\})\s*$") {
            try {
                $adCostPhaseMarker = $Matches[1] | ConvertFrom-Json
            } catch {
                Write-Host "TIKTOK_AD_COST_MARKER_PARSE_FAILED: phase complete"
            }
        }
    }
    if ($task.executor -in @("tk-bill", "tk-bill-cn")) {
        Merge-TikTokAdCostMarkers `
            -Task $task `
            -Discovered $adCostDiscoveredMarker `
            -AccountResults $adCostAccountResultMarkers `
            -Phase $adCostPhaseMarker
        if (
            $joined.Contains(
                "TIKTOK_AD_COST_RECOVERABLE_SESSION_PRESERVED"
            )
        ) {
            Set-ObjectProperty `
                -Object $task `
                -Name "sessionDisposition" `
                -Value "preserved_recoverable"
        } elseif (
            $joined.Contains(
                "TIKTOK_AD_COST_MANUAL_VERIFICATION_SESSION_PRESERVED"
            ) -or
            $joined.Contains(
                "TIKTOK_AD_COST_MANUAL_VERIFICATION_PENDING"
            ) -or
            $joined.Contains("TIKTOK_EXPORT_SUBMITTED_SESSION_PRESERVED")
        ) {
            Set-ObjectProperty `
                -Object $task `
                -Name "sessionDisposition" `
                -Value "preserved_manual"
        } else {
            Set-ObjectProperty `
                -Object $task `
                -Name "sessionDisposition" `
                -Value $null
        }
    }

    if ($joined.Contains("TIKTOK_STORE_DISABLED_CONNECTION_STOPPED")) {
        $task.status = "no_data"
        $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5peg5pWw5o2u"))
        $task.terminalCode = "TIKTOK_STORE_DISABLED"
        $task.message = "TikTok explicitly reported that the account is disabled or the store status is invalid; the exact shop was force-closed."
    } elseif ($confirmedFiles.Count -gt 0) {
        $deliveryFiles = @()
        foreach ($confirmedFile in @($confirmedFiles | Select-Object -Unique)) {
            $deliveryFiles += Move-ConfirmedFile -Task $task -SourcePath $confirmedFile
        }
        $task.files = @($deliveryFiles | Select-Object -Unique)
        if (Test-ConfirmedFiles -Task $task) {
            $task.status = "completed"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5a6M5oiQ"))
            $task.terminalCode = "TIKTOK_EXPORT_FILE_CONFIRMED"
            $task.message = ""
        } else {
            $task.status = "failed"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5pyq5a6M5oiQ"))
            $task.terminalCode = "FILE_CONFIRMATION_FAILED"
            $task.message = "The reported file was missing, empty, or still downloading."
        }
    } elseif ($joined.Contains("TIKTOK_NO_DATA_FOR_PERIOD")) {
        $task.status = "no_data"
        $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5peg5pWw5o2u"))
        $task.terminalCode = "TIKTOK_NO_DATA_FOR_PERIOD"
        $task.message = "No report was generated for the requested date range."
    } elseif (
        $joined.Contains("TIKTOK_SELLER_HOME_NOT_REACHED_CONNECTION_STOPPED") -or
        $joined.Contains("STOP_REQUESTED_AFTER_CONNECTION_FAILURE")
    ) {
        $task.status = "manual_required"
        $task.statusText = [System.Text.Encoding]::UTF8.GetString(
            [System.Convert]::FromBase64String("5pyq6L+b6aaW6aG177yM6K+35qOA5p+l55m75b2V")
        )
        $task.terminalCode = "TIKTOK_SELLER_HOME_NOT_REACHED"
        $task.message = "TikTok did not reach Seller Center home; the exact shop connection was closed."
    } elseif (
        $joined.Contains("TIKTOK_SELLER_CENTER_DETECTED") -and
        -not $joined.Contains("TIKTOK_EXPORT_EXECUTION_STARTED")
    ) {
        $task.status = "failed"
        $task.statusText = [System.Text.Encoding]::UTF8.GetString(
            [System.Convert]::FromBase64String("5pyq5a6M5oiQ")
        )
        $task.terminalCode = "TIKTOK_EXECUTION_NOT_STARTED_AFTER_HOME"
        $task.message = "TikTok reached Seller Center home but did not start the bill workflow; this is a script defect, not an executing task."
    } elseif ($joined.Contains("STOPPED_AFTER_LOGIN_TIMEOUT")) {
        $task.status = "manual_required"
        $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5b6F5Lq65bel"))
        $task.terminalCode = "STOPPED_AFTER_LOGIN_TIMEOUT"
        $task.message = "TikTok login or verification requires manual completion."
    } elseif ($joined.Contains("TIKTOK_EXPORT_SUBMITTED_SESSION_PRESERVED")) {
        $task.status = "manual_required"
        $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5b6F5Lq65bel"))
        $task.terminalCode = "TIKTOK_EXPORT_SUBMITTED_SESSION_PRESERVED"
        $task.message = "The export was submitted and the live session was preserved."
    } elseif ($joined.Contains("STOPPED_AFTER_STAGE_FAILURE")) {
        $stageFailure = @(
            $transcript | Where-Object { $_ -match "^TIKTOK_STAGE_FAILED:\s*" }
        ) | Select-Object -Last 1
        $task.status = "failed"
        $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5pyq5a6M5oiQ"))
        $task.terminalCode = "TIKTOK_STAGE_FAILED"
        $task.message = if ($stageFailure) {
            "$stageFailure"
        } else {
            "TikTok failed during a bounded execution stage; the shop session was closed."
        }
    } elseif ($joined.Contains("STOPPED_AFTER_EXPORT_TIMEOUT")) {
        $task.status = "failed"
        $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5pyq5a6M5oiQ"))
        $task.terminalCode = "STOPPED_AFTER_EXPORT_TIMEOUT"
        $task.message = "TikTok did not produce the report within ten minutes."
    } else {
        $task.status = "failed"
        $task.statusText = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("5pyq5a6M5oiQ"))
        $task.terminalCode = "TK_BILL_EXIT_$taskExitCode"
        $task.message = "tk-bill ended without a confirmed report file."
    }

    if ($task.executor -in @("tk-bill", "tk-bill-cn")) {
        if ($joined.Contains("TIKTOK_STORE_DISABLED_CONNECTION_STOPPED")) {
            Set-ObjectProperty -Object $task -Name "incomeStatus" -Value "no_data"
            Set-ObjectProperty -Object $task -Name "incomeTerminalCode" -Value (
                "TIKTOK_STORE_DISABLED"
            )
            Set-ObjectProperty -Object $task -Name "adCostStatus" -Value "completed"
            Set-ObjectProperty -Object $task -Name "adCostTerminalCode" -Value (
                "TIKTOK_STORE_DISABLED"
            )
            Set-ObjectProperty -Object $task -Name "adAccountCount" -Value 0
            Set-ObjectProperty -Object $task -Name "adAccountExports" -Value @()
        } elseif ($confirmedFiles.Count -gt 0 -and (Test-ConfirmedFiles -Task $task)) {
            Set-ObjectProperty -Object $task -Name "incomeStatus" -Value "completed"
            Set-ObjectProperty -Object $task -Name "incomeTerminalCode" -Value (
                "TIKTOK_EXPORT_FILE_CONFIRMED"
            )
        } elseif ($joined.Contains("TIKTOK_NO_DATA_FOR_PERIOD")) {
            Set-ObjectProperty -Object $task -Name "incomeStatus" -Value "no_data"
            Set-ObjectProperty -Object $task -Name "incomeTerminalCode" -Value (
                "TIKTOK_NO_DATA_FOR_PERIOD"
            )
        } elseif ("$($task.incomeStatus)" -notin @("completed", "no_data")) {
            Set-ObjectProperty -Object $task -Name "incomeStatus" -Value $task.status
            Set-ObjectProperty -Object $task -Name "incomeTerminalCode" -Value (
                $task.terminalCode
            )
        }

        if (
            $joined.Contains(
                "TIKTOK_AD_LOGIN_CREDENTIALS_REJECTED_CONNECTION_STOPPED"
            )
        ) {
            $incomeResultText = if ($task.incomeStatus -eq "completed") {
                [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String(
                        "5pS25YWl6KGo5oiQ5Yqf"
                    )
                )
            } elseif ($task.incomeStatus -eq "no_data") {
                [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String(
                        "5pS25YWl6KGo5peg5pWw5o2u"
                    )
                )
            } else {
                [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String(
                        "5pS25YWl6KGo5pyq5a6M5oiQ"
                    )
                )
            }
            $adLoginCredentialsFailureText = (
                [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String(
                        "77yM5bm/5ZGK6LSm5Y2V5aSx6LSl77yI5bm/5ZGK6LSm5Y+35a+G56CB6ZSZ6K+v77yJ"
                    )
                )
            )
            $task.status = "failed"
            $task.statusText = "$incomeResultText$adLoginCredentialsFailureText"
            $task.terminalCode = "TIKTOK_AD_LOGIN_CREDENTIALS_REJECTED"
            $task.adCostStatus = "failed"
            $task.adCostTerminalCode = $task.terminalCode
            $task.message = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String(
                    "5bm/5ZGK6LSm5Y+35a+G56CB6ZSZ6K+v"
                )
            )
        } elseif ($joined.Contains("TIKTOK_AD_LOGIN_OTP_REQUIRED_CONNECTION_STOPPED")) {
            $incomeResultText = if ($task.incomeStatus -eq "completed") {
                [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String(
                        "5pS25YWl6KGo5oiQ5Yqf"
                    )
                )
            } elseif ($task.incomeStatus -eq "no_data") {
                [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String(
                        "5pS25YWl6KGo5peg5pWw5o2u"
                    )
                )
            } else {
                [System.Text.Encoding]::UTF8.GetString(
                    [System.Convert]::FromBase64String(
                        "5pS25YWl6KGo5pyq5a6M5oiQ"
                    )
                )
            }
            $adLoginOtpFailureText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String(
                    "77yM5bm/5ZGK6LSm5Y2V5aSx6LSl77yI5bm/5ZGK55m75b2V6ZyA6KaB6aqM6K+B56CB77yJ"
                )
            )
            $task.status = "failed"
            $task.statusText = "$incomeResultText$adLoginOtpFailureText"
            $task.terminalCode = "TIKTOK_AD_LOGIN_OTP_REQUIRED"
            $task.adCostStatus = "failed"
            $task.adCostTerminalCode = $task.terminalCode
            $task.message = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String(
                    "5bm/5ZGK55m75b2V6ZyA6KaB6aqM6K+B56CB"
                )
            )
        } elseif ($joined.Contains("TIKTOK_STORE_DISABLED_CONNECTION_STOPPED")) {
            $task.status = "no_data"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("5peg5pWw5o2u")
            )
            $task.terminalCode = "TIKTOK_STORE_DISABLED"
            $task.message = (
                "TikTok explicitly reported that the account is disabled or " +
                "the store status is invalid; the exact shop was force-closed."
            )
        } elseif (Test-TikTokCombinedTerminal -Task $task) {
            $task.status = "completed"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("5a6M5oiQ")
            )
            $task.terminalCode = "TIKTOK_INCOME_AND_AD_COST_COMPLETE"
            $task.message = ""
        } elseif (
            (Test-TikTokIncomeTerminal -Task $task) -and
            (
                $joined.Contains(
                    "TIKTOK_AD_COST_MANUAL_VERIFICATION_SESSION_PRESERVED"
                ) -or
                $joined.Contains(
                    "TIKTOK_AD_COST_MANUAL_VERIFICATION_PENDING"
                )
            )
        ) {
            $task.status = "manual_required"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("5b6F5Lq65bel")
            )
            $task.terminalCode = (
                "TIKTOK_AD_COST_MANUAL_VERIFICATION_SESSION_PRESERVED"
            )
            $task.adCostStatus = "manual_required"
            $task.adCostTerminalCode = $task.terminalCode
            $task.message = (
                "Advertising cost export requires manual verification in the " +
                "preserved exact Feikua shop window."
            )
        } elseif (Test-TikTokIncomeTerminal -Task $task) {
            $task.status = "failed"
            $task.statusText = [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String("5pyq5a6M5oiQ")
            )
            $task.terminalCode = if ($task.adCostTerminalCode) {
                $task.adCostTerminalCode
            } else {
                "TIKTOK_AD_COST_PHASE_INCOMPLETE"
            }
            $task.message = (
                "The income stage is terminal, but not every discovered " +
                "advertising account has a confirmed cost file or explicit no-data result."
            )
        }
    }

    $task.updatedAt = [datetime]::UtcNow.ToString("o")
    Save-JsonAtomic -Path $tasksPath -Value $plan
}

$completed = @($selectedTasks | Where-Object { $_.status -eq "completed" }).Count
$noData = @($selectedTasks | Where-Object { $_.status -eq "no_data" }).Count
$manual = @($selectedTasks | Where-Object { $_.status -eq "manual_required" }).Count
$failed = @($selectedTasks | Where-Object { $_.status -eq "failed" }).Count
$unsupported = @($selectedTasks | Where-Object { $_.status -eq "unsupported" }).Count

if (-not $SkipFinalizer) {
    Invoke-WorkbookFinalizer -TasksPath $tasksPath
}

Write-Host "MONTHLY_BILL_EXECUTION_COMPLETE"
Write-Host "selected_tasks: $($selectedTasks.Count)"
Write-Host "completed: $completed"
Write-Host "no_data: $noData"
Write-Host "manual_required: $manual"
Write-Host "failed: $failed"
Write-Host "unsupported: $unsupported"
Write-Host "tasks_path: $tasksPath"
Write-Host "balance_root: $($plan.balanceRoot)"
Write-Host "balance_detail_root: $($plan.balanceDetailRoot)"
Write-Host "ad_cost_root: $($plan.adCostRoot)"
