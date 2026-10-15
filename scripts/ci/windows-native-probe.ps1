# Disposable original-client observation. Does not install, log in, or publish.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Join-Path $env:RUNNER_TEMP ("heyi-native-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $root | Out-Null
$env:HEYI_NATIVE_PROBE_ROOT = $root
if ($env:GITHUB_ENV) { "HEYI_NATIVE_PROBE_ROOT=$root" | Out-File -FilePath $env:GITHUB_ENV -Append -Encoding utf8 }
$installer = Join-Path $root 'original-installer.exe'
$extract = Join-Path $root 'original'
$rulePrefix = 'HeyiNativeProbe-' + [guid]::NewGuid().ToString('N')
$rules = @()
$app = $null
$probeStarted = Get-Date
$resultPath = Join-Path $root 'ci-result.json'
$result = @{ ok = $false; originalExecuted = $false; installerSha256 = $null }
try {
    # Direct HTTPS, without inheriting runner proxy settings.
    Add-Type -AssemblyName System.Net.Http
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.UseProxy = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(150)
    try {
        $response = $client.GetAsync('https://d.zhipin.com/static/zhipin/desktop/win/boss-zhipin-1.7.5.exe').GetAwaiter().GetResult()
        $response.EnsureSuccessStatusCode() | Out-Null
        $stream = [System.IO.File]::Create($installer)
        try { $response.Content.CopyToAsync($stream).GetAwaiter().GetResult() | Out-Null } finally { $stream.Dispose() }
    } finally {
        $client.Dispose()
        $handler.Dispose()
    }
    $expectedInstaller = '56fcb61359f1dedda66d68b5dec34021d24107ef34a4cf607a1166bf2e08404c'
    $result.installerSha256 = (Get-FileHash $installer -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($result.installerSha256 -ne $expectedInstaller) { throw 'Official installer differs from the reviewed user input' }
    $sevenZip = Join-Path $env:ProgramFiles '7-Zip\7z.exe'
    if (!(Test-Path $sevenZip)) { throw '7-Zip is required; do not run the original installer' }
    & $sevenZip x $installer "-o$extract" -y -bso0 -bsp0
    if ($LASTEXITCODE -ne 0) { throw 'Installer extraction failed' }
    $apps = @(Get-ChildItem $extract -Filter 'boss-zhipin.exe' -Recurse -File)
    if ($apps.Count -eq 0) {
        $archives = @(Get-ChildItem $extract -Filter 'app-32.7z' -Recurse -File)
        if ($archives.Count -ne 1) { throw 'Expected one original x86 application archive' }
        $payload = Join-Path $extract 'payload'
        & $sevenZip x $archives[0].FullName "-o$payload" -y -bso0 -bsp0
        if ($LASTEXITCODE -ne 0) { throw 'Application archive extraction failed' }
        $apps = @(Get-ChildItem $payload -Filter 'boss-zhipin.exe' -Recurse -File)
    }
    if ($apps.Count -ne 1) { throw 'Expected exactly one original main executable' }
    $app = $apps[0].FullName
    $expectedApp = 'be47a9065167f57d43933f0d6cb1c6f764084dcc3ba88ef47ba212283d6e953f'
    if ((Get-FileHash $app -Algorithm SHA256).Hash -ine $expectedApp) { throw 'Original application fingerprint mismatch' }
    $resources = Join-Path (Split-Path $app) 'resources'
    $expectedResources = @{
        'app.asar' = '0db840a254925923b26e298572869828d8195ced1a12fb708ad2ab800e8494af'
        'app.asar.bzlmanifest' = '0eaf12aaf926040f3df48a4ac9ce7201e1db87fdba22475a17c509b0a15439ea'
    }
    foreach ($name in $expectedResources.Keys) {
        $file = Join-Path $resources $name
        if (!(Test-Path $file) -or (Get-FileHash $file -Algorithm SHA256).Hash -ine $expectedResources[$name]) {
            throw "Original application resource mismatch: $name"
        }
    }
    $result.resourcesVerified = $true
    $executables = @(Get-ChildItem (Split-Path $app) -Filter '*.exe' -Recurse -File)
    # Hard isolation also covers vendor helper/daemon executables.
    foreach ($file in $executables) {
        $name = "$rulePrefix-$($rules.Count)"
        New-NetFirewallRule -DisplayName $name -Direction Outbound -Program $file.FullName -Action Block -Profile Any -Enabled True | Out-Null
        $rules += $name
    }
    foreach ($name in $rules) {
        $rule = Get-NetFirewallRule -DisplayName $name
        if ($rule.Action -ne 'Block' -or $rule.Enabled -ne 'True') { throw 'OS network boundary not active' }
    }
    @{ verified = $true; executables = $executables.Count; originalProfileUsed = $false } |
        ConvertTo-Json | Set-Content (Join-Path $root 'firewall.json') -Encoding utf8NoBOM
    # Control run: same pinned, firewalled program without inspector/injection.
    $baseline = Join-Path $root 'baseline'
    foreach ($folder in @('app-data','local-app-data','user','temp','profile')) {
        New-Item -ItemType Directory -Force (Join-Path $baseline $folder) | Out-Null
    }
    $baselineEnv = @{
        APPDATA = Join-Path $baseline 'app-data'
        LOCALAPPDATA = Join-Path $baseline 'local-app-data'
        USERPROFILE = Join-Path $baseline 'user'
        TEMP = Join-Path $baseline 'temp'
        TMP = Join-Path $baseline 'temp'
        NODE_OPTIONS = $null
        NODE_PATH = $null
        ELECTRON_RUN_AS_NODE = $null
    }
    $baselineProcess = Start-Process -FilePath $app -WorkingDirectory (Split-Path $app) `
        -ArgumentList @('--disable-gpu', "--user-data-dir=`"$(Join-Path $baseline 'profile')`"") `
        -Environment $baselineEnv -PassThru
    $exited = $baselineProcess.WaitForExit(10000)
    $baselineResult = @{
        exitedWithinTenSeconds = $exited
        exitCode = $(if ($exited) { $baselineProcess.ExitCode } else { $null })
        mainWindowPresent = ($baselineProcess.MainWindowHandle -ne [IntPtr]::Zero)
        inspectorAttached = $false
        ownCodeInjected = $false
    }
    $baselineResult | ConvertTo-Json | Set-Content (Join-Path $root 'baseline.json') -Encoding utf8NoBOM
    $baselinePrefix = [System.IO.Path]::GetFullPath($root) + [System.IO.Path]::DirectorySeparatorChar
    Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($baselinePrefix, [System.StringComparison]::OrdinalIgnoreCase)
    } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    if (!$exited) { $baselineProcess.WaitForExit(5000) | Out-Null }
    $result.originalExecuted = $true
    & node (Join-Path $PSScriptRoot '..\..\hr-desktop\client-workbench\probe-windows-runtime.cjs') $app $root
    if ($LASTEXITCODE -ne 0) { throw 'Windows original-runtime probe did not complete' }
    $result.ok = $true
} catch {
    $result.error = $_.Exception.Message
    throw
} finally {
    # Keep only native crash classification fields for this exact disposable
    # main executable. Never copy event messages, dump files or user profiles.
    $nativeEvents = @{ checked = $false; matches = @() }
    if ($app) {
        try {
            $events = @(Get-WinEvent -FilterHashtable @{
                LogName = 'Application'; Id = 1000; StartTime = $probeStarted
            } -ErrorAction Stop)
            $nativeEvents.checked = $true
            foreach ($event in $events) {
                $fields = @{}
                ([xml]$event.ToXml()).Event.EventData.Data | ForEach-Object {
                    if ($_.Name) { $fields[[string]$_.Name] = [string]$_.'#text' }
                }
                if ($fields['AppPath'] -and
                    $fields['AppPath'].Equals($app, [System.StringComparison]::OrdinalIgnoreCase)) {
                    $nativeEvents.matches += @{
                        application = 'boss-zhipin.exe'
                        module = [System.IO.Path]::GetFileName($fields['ModuleName'])
                        exceptionCode = $(if ($fields['ExceptionCode'] -match '^[0-9a-fA-Fx]{1,16}$') {
                            $fields['ExceptionCode']
                        } else { $null })
                        faultOffset = $(if ($fields['FaultingOffset'] -match '^[0-9a-fA-Fx]{1,20}$') {
                            $fields['FaultingOffset']
                        } else { $null })
                    }
                }
                if ($nativeEvents.matches.Count -ge 5) { break }
            }
        } catch {
            if ($_.CategoryInfo.Category -eq 'ObjectNotFound') { $nativeEvents.checked = $true }
        }
    }
    $nativeEvents | ConvertTo-Json -Depth 6 |
        Set-Content (Join-Path $root 'native-crash-events.json') -Encoding utf8NoBOM
    # Stop only processes whose executable is inside this disposable directory.
    $prefix = [System.IO.Path]::GetFullPath($root) + [System.IO.Path]::DirectorySeparatorChar
    Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)
    } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    foreach ($name in $rules) { Remove-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue }
    $result | ConvertTo-Json -Depth 6 | Set-Content $resultPath -Encoding utf8NoBOM
    # Small own reports stay in Actions logs: no artifact-storage quota needed.
    # Never print vendor stdout, installer/resources, code, or profile files.
    foreach ($name in @('ci-result.json', 'firewall.json', 'baseline.json', 'entry-runtime.json',
        'runtime-observation.json', 'runtime-log-signals.json', 'native-crash-events.json',
        'sdk-scope-inventory.json', 'matching-runtime-scope-fixture.json')) {
        $report = Join-Path $root $name
        if (Test-Path $report) {
            $data = Get-Content -Raw $report | ConvertFrom-Json
            Write-Output ("HEYI_NATIVE_REPORT " + $name + " " + ($data | ConvertTo-Json -Depth 16 -Compress))
        }
    }
}
