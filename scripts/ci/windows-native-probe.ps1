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
        try { $response.Content.CopyToAsync($stream).GetAwaiter().GetResult() } finally { $stream.Dispose() }
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
    $result.originalExecuted = $true
    & node (Join-Path $PSScriptRoot '..\..\hr-desktop\client-workbench\probe-windows-runtime.cjs') $app $root
    if ($LASTEXITCODE -ne 0) { throw 'Windows original-runtime probe did not complete' }
    $result.ok = $true
} catch {
    $result.error = $_.Exception.Message
    throw
} finally {
    # Stop only processes whose executable is inside this disposable directory.
    $prefix = [System.IO.Path]::GetFullPath($root) + [System.IO.Path]::DirectorySeparatorChar
    Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)
    } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    foreach ($name in $rules) { Remove-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue }
    $result | ConvertTo-Json -Depth 6 | Set-Content $resultPath -Encoding utf8NoBOM
    # Artifacts are explicitly limited to these own reports; never the vendor
    # installer, resources, original code, or temporary profile.
    Write-Output "Own probe reports: $root"
}
