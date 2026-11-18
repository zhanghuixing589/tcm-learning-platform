function Stop-ProjectProcess {
    param($Record)
    $process=Get-Process -Id $Record.id -ErrorAction SilentlyContinue
    if (!$process) { return }
    $expectedTicks=$Record.startedTicks
    if (!$expectedTicks -and $Record.startedAt) { $expectedTicks=([datetime]$Record.startedAt).ToUniversalTime().Ticks.ToString() }
    if ($process.StartTime.ToUniversalTime().Ticks.ToString() -ne [string]$expectedTicks) { return }
    $children=Get-CimInstance Win32_Process -Filter "ParentProcessId=$($process.Id)" -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        $childProcess=Get-Process -Id $child.ProcessId -ErrorAction SilentlyContinue
        if ($childProcess) { Stop-ProjectProcess @{id=$childProcess.Id;startedTicks=$childProcess.StartTime.ToUniversalTime().Ticks.ToString()} }
    }
    $process.Refresh()
    if (!$process.HasExited) { Stop-Process -Id $process.Id; $null=$process.WaitForExit(5000) }
}
