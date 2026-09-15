param([Parameter(Mandatory=$true)][string]$Install,
      [Parameter(Mandatory=$true)][string]$Payload,
      [Parameter(Mandatory=$true)][int]$OldPid)
$ErrorActionPreference = 'Stop'
$installPath = [IO.Path]::GetFullPath($Install).TrimEnd('\')
$payloadPath = [IO.Path]::GetFullPath($Payload)
if (-not $payloadPath.StartsWith($installPath + '\.update-stage-', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid staging location' }
$parts = @('app', 'runtime', 'MyAnimeDesk.exe')
foreach ($part in $parts) {
    if (-not (Test-Path -LiteralPath (Join-Path $payloadPath $part))) { throw 'Incomplete update' }
}
$deadline = (Get-Date).AddSeconds(90)
while (Get-Process -Id $OldPid -ErrorAction SilentlyContinue) {
    if ((Get-Date) -gt $deadline) { throw 'App did not exit; no files changed' }
    Start-Sleep -Milliseconds 500
}
$backupPath = Join-Path $installPath ('.update-backup-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $backupPath | Out-Null
$movedOld = @()
$movedNew = @()
try {
    foreach ($part in $parts) {
        Move-Item -LiteralPath (Join-Path $installPath $part) -Destination (Join-Path $backupPath $part)
        $movedOld += $part
        Move-Item -LiteralPath (Join-Path $payloadPath $part) -Destination (Join-Path $installPath $part)
        $movedNew += $part
    }
    $newApp = Start-Process -FilePath (Join-Path $installPath 'MyAnimeDesk.exe') -WorkingDirectory $installPath -WindowStyle Hidden -PassThru
    Start-Sleep -Seconds 8
    $newApp.Refresh()
    if ($newApp.HasExited -and $newApp.ExitCode -ne 0) { throw 'New version failed to start' }
} catch {
    $_ | Out-String | Set-Content -LiteralPath (Join-Path $backupPath 'update-error.txt')
    foreach ($part in $movedNew) {
        Move-Item -LiteralPath (Join-Path $installPath $part) -Destination (Join-Path $payloadPath $part)
    }
    foreach ($part in $movedOld) {
        Move-Item -LiteralPath (Join-Path $backupPath $part) -Destination (Join-Path $installPath $part)
    }
    Start-Process -FilePath (Join-Path $installPath 'MyAnimeDesk.exe') -WorkingDirectory $installPath -WindowStyle Hidden
}
# Keep the previous application recoverable. User data lives separately in .myanimedesk.
