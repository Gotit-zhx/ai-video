# Same native checks as .github/workflows/hr-desktop.yml. No production
# credentials, release publication, candidate messages or signed-in profiles.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$env:PYTHONUTF8 = '1'
$env:CSC_IDENTITY_AUTO_DISCOVERY = 'false'
$taskTools = Join-Path $env:TEMP 'heyi-desktop-tools'
New-Item -ItemType Directory -Force $taskTools | Out-Null

function Run-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed (exit $LASTEXITCODE)" }
}

# Portable Node avoids changing the image's shared tool installation.
$nodeVersion = '22.14.0'
$nodeArchive = "node-v$nodeVersion-win-x64.zip"
$nodeBase = "https://nodejs.org/dist/v$nodeVersion"
Invoke-WebRequest "$nodeBase/$nodeArchive" -OutFile "$taskTools\$nodeArchive" -UseBasicParsing
$sums = (Invoke-WebRequest "$nodeBase/SHASUMS256.txt" -UseBasicParsing).Content
$checksumLine = @($sums -split "`n" | Where-Object { $_.Trim().EndsWith("  $nodeArchive") })
if ($checksumLine.Count -ne 1) { throw 'Missing official Node checksum' }
$expectedHash = ($checksumLine[0] -split '\s+')[0]
if ((Get-FileHash "$taskTools\$nodeArchive" -Algorithm SHA256).Hash -ine $expectedHash) {
    throw 'Node archive checksum mismatch'
}
Expand-Archive "$taskTools\$nodeArchive" -DestinationPath $taskTools -Force
$env:PATH = "$taskTools\node-v$nodeVersion-win-x64;$env:PATH"

# Explicit Python 3.12, matching Actions; verify the Windows publisher before
# running the official installer. Installation is isolated to this disposable VM.
$pythonSetup = Join-Path $taskTools 'python-3.12.10-amd64.exe'
Invoke-WebRequest 'https://www.python.org/ftp/python/3.12.10/python-3.12.10-amd64.exe' -OutFile $pythonSetup -UseBasicParsing
$signature = Get-AuthenticodeSignature $pythonSetup
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'Python Software Foundation') {
    throw 'Python installer publisher verification failed'
}
$pythonRoot = Join-Path $taskTools 'python312'
$process = Start-Process -FilePath $pythonSetup -ArgumentList @(
    '/quiet', 'InstallAllUsers=0', "TargetDir=`"$pythonRoot`"",
    'Include_launcher=0', 'Include_test=0', 'PrependPath=0'
) -Wait -PassThru
if ($process.ExitCode -notin @(0, 3010)) { throw "Python setup failed: $($process.ExitCode)" }
$env:PATH = "$pythonRoot;$pythonRoot\Scripts;$env:PATH"
$python = Join-Path $pythonRoot 'python.exe'
Run-Checked 'npm.cmd' @('install', '--global', 'pnpm@11.25.0')

Push-Location (Join-Path $PSScriptRoot '..\..\hr-desktop')
try {
    Run-Checked 'pnpm.cmd' @('install', '--frozen-lockfile')
    Run-Checked 'node.exe' @('node_modules/electron/install.js')
    Run-Checked $python @('runtime/install.py')
    Run-Checked $python @('runtime/build.py')
    Run-Checked 'npm.cmd' @('test')
    foreach ($testFile in @(
        'test_passive_runtime.py', 'test_active_sourcing.py',
        'test_account_history.py', 'test_local_session.py',
        'test_managed_jobs.py', 'test_conversation_scope.py'
    )) {
        Run-Checked $python @('-m', 'unittest', 'discover', '-s', 'runtime', '-p', $testFile)
    }
    foreach ($testFile in @(
        'test_recruitment_demands.py', 'test_passive_pool.py',
        'test_hr_sourcing.py', 'test_resume_identity.py', 'test_desktop_bridge.py'
    )) {
        Run-Checked $python @('-m', 'unittest', 'discover', '-s', '../scripts/boss-agent', '-p', $testFile)
    }
    Run-Checked $python @('runtime/test_portable.py')
    Run-Checked $python @('runtime/test_direct_network.py')
    $env:HEYI_TEST_USER_DATA = Join-Path $env:TEMP 'heyi-backup-source-profile'
    $env:HEYI_TEST_OUTPUT = Join-Path $PWD 'test-results\source'
    Run-Checked 'npm.cmd' @('run', 'smoke')
    Run-Checked 'npm.cmd' @('run', 'dist:win')
    $env:HEYI_TEST_USER_DATA = Join-Path $env:TEMP 'heyi-backup-packaged-profile'
    $env:HEYI_TEST_OUTPUT = Join-Path $PWD 'test-results\packaged'
    # Keep this script ASCII for Windows PowerShell 5.1 source decoding.
    $apps = @(Get-ChildItem 'release\win-unpacked\*.exe' -File)
    if ($apps.Count -ne 1) { throw 'Expected exactly one packaged application' }
    $app = $apps[0].FullName
    $process = Start-Process -FilePath $app -ArgumentList '--self-test' -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "Packaged smoke failed: $($process.ExitCode)" }
    if (!(Test-Path "$env:HEYI_TEST_OUTPUT\smoke.json")) { throw 'Missing packaged smoke result' }
    Run-Checked $python @('../scripts/ci/desktop-manifest.py')
    # Upload installers and results only, without duplicate unpacked binaries.
    Remove-Item 'release\win-unpacked' -Recurse -Force
} finally {
    Pop-Location
}
