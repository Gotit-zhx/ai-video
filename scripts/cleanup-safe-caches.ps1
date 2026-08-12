[CmdletBinding(SupportsShouldProcess)]
param()

$cleanupRoots = @(
  (Join-Path $env:LOCALAPPDATA "npm-cache"),
  (Join-Path $env:LOCALAPPDATA "Temp"),
  (Join-Path $env:LOCALAPPDATA "uv\cache"),
  (Join-Path $env:LOCALAPPDATA "pip\cache"),
  (Join-Path $env:LOCALAPPDATA "electron\Cache"),
  (Join-Path $env:LOCALAPPDATA "electron-builder\Cache")
)

$allowedRoots = @(
  [IO.Path]::GetFullPath($env:LOCALAPPDATA),
  [IO.Path]::GetFullPath($env:APPDATA)
)

function Get-DirectoryBytes([string]$path) {
  if (-not (Test-Path -LiteralPath $path)) { return [int64]0 }
  $measure = Get-ChildItem -LiteralPath $path -Force -File -Recurse -ErrorAction SilentlyContinue |
    Measure-Object -Property Length -Sum
  return [int64]($measure.Sum -as [int64])
}

function Assert-SafeCachePath([string]$path) {
  $fullPath = [IO.Path]::GetFullPath($path).TrimEnd([IO.Path]::DirectorySeparatorChar)
  if ([string]::IsNullOrWhiteSpace($fullPath)) { throw "Cache path is empty" }
  if ($fullPath -eq [IO.Path]::GetFullPath($env:LOCALAPPDATA).TrimEnd([IO.Path]::DirectorySeparatorChar)) {
    throw "Refusing to delete the LocalAppData root"
  }
  if ($fullPath -eq [IO.Path]::GetFullPath($env:APPDATA).TrimEnd([IO.Path]::DirectorySeparatorChar)) {
    throw "Refusing to delete the AppData root"
  }
  $isAllowed = $allowedRoots | Where-Object {
    $root = $_.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $fullPath.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)
  }
  if (-not $isAllowed) { throw "Cache path is outside the allowed roots: $fullPath" }
  return $fullPath
}

$before = [int64]0
$existingRoots = foreach ($root in $cleanupRoots) {
  $safeRoot = Assert-SafeCachePath $root
  if (Test-Path -LiteralPath $safeRoot) {
    $before += Get-DirectoryBytes $safeRoot
    $safeRoot
  }
}

foreach ($root in $existingRoots) {
  Get-ChildItem -LiteralPath $root -Force -ErrorAction SilentlyContinue | ForEach-Object {
    if ($PSCmdlet.ShouldProcess($_.FullName, "Remove cache content")) {
      Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
    }
  }
}

$after = [int64]0
foreach ($root in $existingRoots) { $after += Get-DirectoryBytes $root }

[pscustomobject]@{
  CleanedGB = [math]::Round(($before - $after) / 1GB, 2)
  RemainingMB = [math]::Round($after / 1MB, 1)
  FreeGB = [math]::Round((Get-PSDrive -Name C).Free / 1GB, 2)
}
