$ErrorActionPreference = 'Stop'

# A systemd service alone does not keep WSL alive. Keep a foreground Linux
# process attached to a hidden Windows launcher while local tests are running.
& wsl.exe -d Ubuntu-24.04 -u root --exec systemctl start redis-server
if ($LASTEXITCODE -ne 0) { throw 'Could not start Redis in Ubuntu-24.04.' }

$existing = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'wsl.exe' -and
    $_.CommandLine -match 'Ubuntu-24\.04' -and
    $_.CommandLine -match 'sleep infinity'
}
if (-not $existing) {
    $keeper = Start-Process -FilePath wsl.exe -WindowStyle Hidden -ArgumentList @(
        '-d', 'Ubuntu-24.04', '-u', 'root', '--exec', 'sleep', 'infinity'
    ) -PassThru
    Write-Output "WSL keep-alive launcher PID: $($keeper.Id)"
}
& wsl.exe -d Ubuntu-24.04 -u root --exec redis-cli ping
if ($LASTEXITCODE -ne 0) { throw 'Redis PING failed.' }
Write-Output 'Redis is running. Run this script again after a Windows restart.'
Write-Output 'To stop this Ubuntu instance after testing: wsl --terminate Ubuntu-24.04'
