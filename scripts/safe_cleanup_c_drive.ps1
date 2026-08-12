$ErrorActionPreference = 'Stop'

$workspaceRoot = 'C:\Users\yingdao\Desktop\web_SDK_项目'
$releaseRoot = Join-Path $workspaceRoot 'automation-agent'
$dockerUpdateTemp = 'C:\Users\yingdao\AppData\Local\Temp\DockerDesktopUpdates'
$dockerVhd = 'C:\Users\yingdao\AppData\Local\Docker\wsl\disk\docker_data.vhdx'
$keepReleaseNames = @('release', 'release-agent-expanded-v27')
$containerNames = @(
    'ai-commerce-mysql',
    'ai-commerce-redis',
    'heyi-pi-runtime',
    'ai-commerce-app',
    'ai-commerce-frontend'
)
$logPath = Join-Path $workspaceRoot 'safe-cleanup-result.log'

function Write-Log([string]$Message) {
    $line = '{0:o} {1}' -f (Get-Date), $Message
    $line | Tee-Object -FilePath $logPath -Append
}

function Assert-Admin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'This cleanup script must be run from an Administrator PowerShell window.'
    }
}

function Assert-ChildPath([string]$Parent, [string]$Candidate) {
    $resolvedParent = [IO.Path]::GetFullPath($Parent).TrimEnd('\')
    $resolvedCandidate = [IO.Path]::GetFullPath($Candidate)
    if (-not $resolvedCandidate.StartsWith($resolvedParent + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing unsafe path outside $resolvedParent : $resolvedCandidate"
    }
}

Assert-Admin
Set-Content -LiteralPath $logPath -Value '' -Encoding UTF8

$freeBefore = (Get-PSDrive C).Free
Write-Log ('C drive free before: {0:N2} GB' -f ($freeBefore / 1GB))

# Remove only old generated release directories. Keep the canonical release and newest v27 build.
$resolvedReleaseRoot = (Resolve-Path -LiteralPath $releaseRoot).Path
$oldReleases = @(
    Get-ChildItem -LiteralPath $resolvedReleaseRoot -Directory -Force |
        Where-Object { $_.Name -like 'release*' -and $_.Name -notin $keepReleaseNames }
)
foreach ($directory in $oldReleases) {
    Assert-ChildPath -Parent $resolvedReleaseRoot -Candidate $directory.FullName
}
Write-Log ("Deleting {0} old release directories; keeping: {1}" -f $oldReleases.Count, ($keepReleaseNames -join ', '))
foreach ($directory in $oldReleases) {
    [IO.Directory]::Delete($directory.FullName, $true)
}

# Remove only the known Docker Desktop updater staging directory.
if (Test-Path -LiteralPath $dockerUpdateTemp) {
    $resolvedTempParent = (Resolve-Path -LiteralPath (Split-Path -Parent $dockerUpdateTemp)).Path
    Assert-ChildPath -Parent $resolvedTempParent -Candidate $dockerUpdateTemp
    Write-Log "Deleting Docker updater staging directory: $dockerUpdateTemp"
    [IO.Directory]::Delete($dockerUpdateTemp, $true)
}

# Docker cache/image cleanup is idempotent and does not touch active containers or volumes.
Write-Log 'Pruning unused Docker build cache and images.'
docker builder prune --all --force | Out-Null
docker image prune --all --force | Out-Null

# Compact the VHDX so the space already freed inside Docker returns to the Windows C drive.
$dockerStopped = $false
try {
    if (Test-Path -LiteralPath $dockerVhd) {
        Write-Log 'Stopping Docker Desktop and WSL for VHDX compaction.'
        docker desktop stop | Out-Null
        $dockerStopped = $true
        wsl.exe --shutdown
        Optimize-VHD -Path $dockerVhd -Mode Full
        Write-Log ('Docker VHDX after compaction: {0:N2} GB' -f ((Get-Item -LiteralPath $dockerVhd).Length / 1GB))
    }
}
finally {
    if ($dockerStopped) {
        Write-Log 'Restarting Docker Desktop.'
        docker desktop start | Out-Null
        $ready = $false
        for ($attempt = 0; $attempt -lt 90; $attempt++) {
            docker info *> $null
            if ($LASTEXITCODE -eq 0) {
                $ready = $true
                break
            }
            Start-Sleep -Seconds 2
        }
        if (-not $ready) {
            throw 'Docker Desktop did not become ready within 180 seconds.'
        }
        docker start $containerNames | Out-Null
    }
}

$freeAfter = (Get-PSDrive C).Free
Write-Log ('C drive free after: {0:N2} GB' -f ($freeAfter / 1GB))
Write-Log ('Space reclaimed from C: {0:N2} GB' -f (($freeAfter - $freeBefore) / 1GB))
Write-Log 'Cleanup completed successfully.'
