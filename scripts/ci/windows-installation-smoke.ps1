param([Parameter(Mandatory = $true)][string]$Root)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# This script must never install/uninstall on a user's workstation.
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_OS -ne 'Windows') {
    throw 'Disposable Windows CI required'
}
$rootPath = [IO.Path]::GetFullPath($Root)
$tempPrefix = [IO.Path]::GetFullPath($env:RUNNER_TEMP) + [IO.Path]::DirectorySeparatorChar
if (!$rootPath.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Installation check must stay inside the runner temporary directory'
}
$boundary = Get-Content (Join-Path $Root 'firewall.json') -Raw | ConvertFrom-Json
if ($boundary.verified -ne $true -or $boundary.workbenchIncluded -ne $true) {
    throw 'Existing verified network boundary required'
}
$identity = 'cn.hoyee.bossworkbench.experimental'
$mutexName = "Local\$identity.running"
$registry = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\${identity}_is1"
$installed = Join-Path $Root 'installed'
$setupDirectory = Join-Path $Root 'setup'
$manifest = Get-Content (Join-Path $setupDirectory 'installer.json') -Raw | ConvertFrom-Json
$setup = Join-Path $setupDirectory $manifest.installer
$entry = Join-Path $installed 'Ai boss.exe'
$uninstaller = Join-Path $installed 'unins000.exe'
$programLink = Join-Path ([Environment]::GetFolderPath('Programs')) 'Ai boss.lnk'
$desktopLink = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Ai boss.lnk'
$profileRoot = Join-Path $env:LOCALAPPDATA 'HeyiBossWorkbench'
$recordsRoot = Join-Path $env:APPDATA 'heyi-recruiter'
$report = @{ ok = $false; realAccountUsed = $false; installerSha256 = $manifest.sha256;
    packageManifestSha256 = $manifest.packageManifestSha256; installerRuns = @() }
$rules = @()
$sentinels = @{}
$stage = 'fresh-runner'
$test = $null

function Invoke-Setup([string]$File, [string[]]$Arguments, [bool]$ShouldSucceed = $true) {
    $log = Join-Path $Root ("installer-" + [guid]::NewGuid().ToString('N') + '.log')
    $process = Start-Process -FilePath $File -ArgumentList ($Arguments + @("/LOG=`"$log`"")) -PassThru
    $completed = $process.WaitForExit(120000)
    $code = if ($completed) { $process.ExitCode } else { $null }
    $report.installerRuns += @{ stage = $stage; expectedSuccess = $ShouldSucceed; completed = $completed; exitCode = $code }
    if (!$completed -or ($ShouldSucceed -and $code -ne 0) -or (!$ShouldSucceed -and $code -eq 0)) {
        if (!$completed -and !$process.HasExited) { $process.Kill($true) }
        # Own installer logs in a disposable runner, never original app logs.
        $tail = if (Test-Path $log) { (Get-Content $log -Tail 12) -join "`n" } else { '' }
        $tail = $tail.Replace($Root, '<fixture>').Replace($env:USERPROFILE, '<user>')
        if ($tail.Length -gt 4000) { $tail = $tail.Substring($tail.Length - 4000) }
        Write-Output ('HEYI_INSTALLER_DIAGNOSTIC ' + (@{ stage = $stage; completed = $completed;
            exitCode = $code; installerLogTail = $tail } | ConvertTo-Json -Compress))
        throw 'Installer lifecycle result did not match the expected outcome'
    }
    return $process.ExitCode
}
function Assert-Preserved {
    foreach ($file in $sentinels.Keys) {
        if (!(Test-Path $file) -or (Get-FileHash $file -Algorithm SHA256).Hash -ne $sentinels[$file]) {
            throw 'Account or recruiter fixture was changed'
        }
    }
}
function Assert-Payload {
    $payload = Get-Content (Join-Path $Root 'workbench/heyi-package.json') -Raw | ConvertFrom-Json
    foreach ($property in $payload.files.PSObject.Properties) {
        $file = Join-Path $installed $property.Name
        if (!(Test-Path $file) -or (Get-FileHash $file -Algorithm SHA256).Hash -ine $property.Value) {
            throw 'Installed payload differs from the verified package'
        }
    }
    if ((Get-FileHash (Join-Path $installed 'heyi-package.json')).Hash -ine $manifest.packageManifestSha256) {
        throw 'Installed package manifest differs'
    }
}
try {
    foreach ($path in @($registry, $installed, $programLink, $desktopLink, $profileRoot, $recordsRoot)) {
        if (Test-Path $path) { throw 'Installation fixture requires empty, disposable locations' }
    }
    if ((Get-FileHash $setup -Algorithm SHA256).Hash -ine $manifest.sha256) {
        throw 'Installer differs from build report'
    }
    foreach ($file in @(
        (Join-Path $profileRoot 'profile/heyi/installer-session-fixture.enc'),
        (Join-Path $recordsRoot 'recruiter/accounts/installer-fixture/records.json')
    )) {
        New-Item -ItemType Directory -Path (Split-Path $file) -Force | Out-Null
        [IO.File]::WriteAllText($file, 'anonymous-installer-preservation-fixture')
        $sentinels[$file] = (Get-FileHash $file -Algorithm SHA256).Hash
    }
    $arguments = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/SP-', '/NORESTART',
        "/DIR=`"$installed`"", '/TASKS=desktopicon')
    $stage = 'first-install'
    Invoke-Setup $setup $arguments | Out-Null
    Assert-Payload
    Assert-Preserved
    $report.installedPayloadVerified = $true
    $registration = Get-ItemProperty $registry
    if ($registration.DisplayName -ne 'Ai boss' -or $registration.InstallLocation.TrimEnd('\') -ne $installed) {
        throw 'Per-user uninstall registration differs'
    }
    $shell = New-Object -ComObject WScript.Shell
    foreach ($link in @($programLink, $desktopLink)) {
        if (!(Test-Path $link) -or $shell.CreateShortcut($link).TargetPath -ne $entry) {
            throw 'Application shortcut does not target this installed entry'
        }
    }
    $report.perUserRegistration = $true
    $report.shortcutsVerified = $true
    # Installer copies create new executable paths: block all of them before
    # starting even the isolated self-test.
    foreach ($file in (Get-ChildItem $installed -Filter '*.exe' -Recurse -File)) {
        $name = 'AiBossInstallProbe-' + [guid]::NewGuid().ToString('N')
        New-NetFirewallRule -DisplayName $name -Direction Outbound -Program $file.FullName `
            -Action Block -Profile Any -Enabled True | Out-Null
        $rules += $name
        $rule = Get-NetFirewallRule -DisplayName $name
        if ($rule.Action -ne 'Block' -or $rule.Enabled -ne 'True') { throw 'Installed executable is not isolated' }
    }
    $report.installedExecutablesNetworkBlocked = $true
    $stage = 'installed-entry-and-live-update-guard'
    $output = Join-Path $Root 'windows-installed-workbench-smoke.json'
    $testEnvironment = @{
        ELECTRON_RUN_AS_NODE = $null
        HEYI_NATIVE_PROBE_ROOT = $Root
        HEYI_WORKBENCH_TEST_DATA = Join-Path $Root 'installed-profile'
        HEYI_WORKBENCH_TEST_PORT = '0'
        HEYI_WORKBENCH_TEST_OUTPUT = $output
        NODE_OPTIONS = $null
        NODE_PATH = $null
    }
    $test = Start-Process -FilePath $entry -WorkingDirectory $installed -ArgumentList '--self-test' `
        -Environment $testEnvironment -PassThru
    $deadline = (Get-Date).AddSeconds(30)
    $mutexFound = $false
    while (!$mutexFound -and (Get-Date) -lt $deadline -and !$test.HasExited) {
        try {
            $mutex = [Threading.Mutex]::OpenExisting($mutexName)
            $mutex.Dispose()
            $mutexFound = $true
        } catch [Threading.WaitHandleCannotBeOpenedException] { Start-Sleep -Milliseconds 100 }
    }
    if (!$mutexFound) { throw 'Actual installed entry did not hold its running guard' }
    Invoke-Setup $setup $arguments $false | Out-Null
    if ($test.HasExited) { throw 'Application did not stay live during update rejection' }
    Invoke-Setup $uninstaller @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART') $false | Out-Null
    if ($test.HasExited -or !(Test-Path $entry)) { throw 'Uninstaller interrupted the live application' }
    $report.runningUpdateRejected = $true
    $report.runningUninstallRejected = $true
    if (!$test.WaitForExit(120000) -or $test.ExitCode -ne 0) { throw 'Installed application check failed' }
    $smoke = Get-Content $output -Raw | ConvertFrom-Json
    if ($smoke.ok -ne $true -or $smoke.entryControllerExitCode -ne 0 -or
        $smoke.shutdown.platformSessionSaved -ne $true -or $smoke.shutdown.realAccountUsed -ne $false) {
        throw 'Installed application did not complete and save its isolated session'
    }
    $report.installedEntryPassed = $true
    # Include the encrypted file actually saved by the installed Electron app.
    $sessionFiles = @(Get-ChildItem (Join-Path $Root 'installed-profile') `
        -Filter 'platform-session.enc' -Recurse -File)
    if ($sessionFiles.Count -ne 1) { throw 'Expected one encrypted session saved by the installed application' }
    $sessionFile = $sessionFiles[0].FullName
    $sentinels[$sessionFile] = (Get-FileHash $sessionFile -Algorithm SHA256).Hash
    $stage = 'overwrite-install'
    Invoke-Setup $setup $arguments | Out-Null
    Assert-Payload
    Assert-Preserved
    $report.overwriteInstallPreservedData = $true
    $userFile = Join-Path $installed 'user-created-file.txt'
    [IO.File]::WriteAllText($userFile, 'untracked file must remain')
    $stage = 'uninstall'
    Invoke-Setup $uninstaller @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART') | Out-Null
    Assert-Preserved
    if ((Test-Path $entry) -or (Test-Path $registry) -or (Test-Path $programLink) -or
        (Test-Path $desktopLink) -or !(Test-Path $userFile)) { throw 'Uninstall ownership boundary failed' }
    $report.uninstallPreservedData = $true
    $report.untrackedFilePreserved = $true
    $report.shortcutsAndRegistrationRemoved = $true
    $stage = 'reinstall'
    Invoke-Setup $setup $arguments | Out-Null
    Assert-Payload
    Assert-Preserved
    $report.reinstallPreservedData = $true
    $stage = 'final-uninstall'
    Invoke-Setup $uninstaller @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART') | Out-Null
    Assert-Preserved
    $report.ok = $true
} finally {
    $report.stage = $stage
    $report | ConvertTo-Json -Depth 6 |
        Set-Content (Join-Path $Root 'windows-installation-smoke.json') -Encoding utf8NoBOM
    # Remove only this probe's rules; the outer runtime boundary stays active.
    if ($report.ok) {
        foreach ($name in $rules) { Remove-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue }
    }
    foreach ($file in $sentinels.Keys) {
        if ($file.StartsWith($profileRoot) -or $file.StartsWith($recordsRoot)) {
            Remove-Item -LiteralPath $file -ErrorAction SilentlyContinue
        }
    }
}
