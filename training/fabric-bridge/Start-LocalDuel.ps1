param(
    [Parameter(Mandatory = $true)][string]$JavaUsername,
    [Parameter(Mandatory = $true)][string]$Checkpoint,
    [Parameter(Mandatory = $true)][string]$HeadlessAgent,
    [string]$HealingCheckpoint,
    [string]$Gradle,
    [string]$Runner,
    [string]$SessionMetadata,
    [string]$ArenaSetup,
    [switch]$BundledAssetsOnly,
    [switch]$Offline,
    [int]$BridgePort = 28774,
    [int]$GamePort = 25568
)

$ErrorActionPreference = 'Stop'
if ($JavaUsername -notmatch '^[A-Za-z0-9_]{3,16}$' -or $JavaUsername -ieq 'Terminator') {
    throw 'Enter your exact Minecraft Java profile name. Terminator is reserved for the bot.'
}
if ($BridgePort -eq $GamePort -or $BridgePort -lt 1024 -or $BridgePort -gt 65535 -or
        $GamePort -lt 1024 -or $GamePort -gt 65535) {
    throw 'Choose two distinct ports between 1024 and 65535.'
}
$Checkpoint = (Resolve-Path -LiteralPath $Checkpoint).Path
$HeadlessAgent = (Resolve-Path -LiteralPath $HeadlessAgent).Path
$runnerArgs = @($BridgePort, $GamePort, $JavaUsername, $Checkpoint)
if ($HealingCheckpoint) {
    $runnerArgs += (Resolve-Path -LiteralPath $HealingCheckpoint).Path
}
if ($SessionMetadata) {
    $runnerArgs += @('--session-metadata', (Resolve-Path -LiteralPath $SessionMetadata).Path)
}
if($ArenaSetup){$runnerArgs+=@('--arena-setup',(Resolve-Path -LiteralPath $ArenaSetup).Path)}
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$Gradle) { $Gradle = Join-Path $PSScriptRoot 'gradlew.bat' }
$Gradle = (Resolve-Path -LiteralPath $Gradle).Path
if (!$Runner) {
    cargo build --locked --manifest-path (Join-Path $workspace 'Cargo.toml') -p terminator-training --example local_duel
    if ($LASTEXITCODE) { throw 'Failed to build the local duel runner.' }
    $Runner = Join-Path $workspace 'target/debug/examples/local_duel.exe'
}
$Runner = (Resolve-Path -LiteralPath $Runner).Path

foreach ($port in $BridgePort, $GamePort) {
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $probe.Start() } finally { $probe.Stop() }
}

$logs = Join-Path $workspace 'runs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$log = Join-Path $logs ("duel-" + [guid]::NewGuid().ToString('N') + '.log')
$hostJob = Start-Job -ArgumentList $PSScriptRoot, $Gradle, $BridgePort, $HeadlessAgent, $log, $BundledAssetsOnly.IsPresent, $Offline.IsPresent -ScriptBlock {
    param($directory, $gradle, $port, $agent, $log, $bundledAssets, $offline)
    Set-Location -LiteralPath $directory
    $arguments = @('--console=plain', 'runClient', '-PduelHost=1', "-PbridgePort=$port", "-PheadlessAgent=$agent")
    if ($bundledAssets) { $arguments += @('-x', 'downloadAssets') }
    if ($offline) { $arguments += '--offline' }
    & $gradle @arguments *> $log
    if ($LASTEXITCODE) { throw "Minecraft worker failed with exit code $LASTEXITCODE." }
}
try {
    Write-Host "Starting the headless host. Log $log"
    $deadline = [datetime]::UtcNow.AddMinutes(15)
    $ready = "Terminator training bridge listening on 127.0.0.1:$BridgePort"
    while (!(Select-String -LiteralPath $log -Pattern $ready -SimpleMatch -Quiet -ErrorAction SilentlyContinue)) {
        if ($hostJob.State -in 'Failed', 'Completed', 'Stopped' -or [datetime]::UtcNow -ge $deadline) {
            throw "The headless host did not start. See $log"
        }
        Start-Sleep -Milliseconds 500
    }
    & $Runner @runnerArgs
    if ($LASTEXITCODE) { throw "The duel ended with an error. See $log" }
    Wait-Job $hostJob -Timeout 20 | Out-Null
} finally {
    Stop-Job $hostJob -ErrorAction SilentlyContinue
    Remove-Job $hostJob -ErrorAction SilentlyContinue
}
