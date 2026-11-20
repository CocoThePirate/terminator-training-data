param(
    [switch]$Headless,
    [switch]$TestMode,
    [switch]$FlatTestWorld,
    [switch]$DatasetOnly,
    [string]$ArenaSetup,
    [string]$DatasetOutput,
    [ValidatePattern('^[A-Za-z0-9_-]{1,64}$')][string]$ParticipantId = 'local-player',
    [ValidateSet('training','validation','evaluation')][string]$ParticipantSplit = 'training',
    [int]$BridgePort = 0,
    [string]$CheckpointPath,
    [string]$JavaUsername,
    [int]$ConnectLocalPort = 0,
    [string]$SessionDirectory
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$tools = (Resolve-Path (Join-Path $repo '../toolchains')).Path
$worker = Join-Path $repo 'target/debug/examples/client_policy.exe'
if (!$DatasetOnly) {
    if (!(Test-Path -LiteralPath $worker)) {throw 'Build the client_policy Rust example before starting the client.'}
    if (!$CheckpointPath) {$CheckpointPath=Join-Path $repo '../../outputs/terminator-neural-checkpoint.json'}
    $CheckpointPath=(Resolve-Path -LiteralPath $CheckpointPath).Path
}
if($ArenaSetup){$ArenaSetup=(Resolve-Path -LiteralPath $ArenaSetup).Path;if(!$DatasetOnly){throw 'ArenaSetup requires DatasetOnly'}}
if ($JavaUsername -and $JavaUsername -notmatch '^[A-Za-z0-9_]{3,16}$') {throw 'Invalid development username'}
if ($ConnectLocalPort -ne 0 -and ($ConnectLocalPort -lt 1024 -or $ConnectLocalPort -gt 65535 -or $FlatTestWorld)) {throw 'Use an unprivileged local port without FlatTestWorld'}
$jar = Join-Path $tools 'baritone-1.13.1/baritone-api-fabric-1.13.1.jar'
$expected = 'bfb79c3cdb8fe1f7697999b1f9c7f08ea29775f3b69d48ad90c387690ee67b05'
if (!(Test-Path -LiteralPath $jar) -or (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    throw 'Pinned Baritone API JAR is missing or has the wrong checksum. See docs/OWN-CLIENT-NAVIGATION.md.'
}
if ($TestMode -and ($BridgePort -lt 1024 -or $BridgePort -gt 65535)) { throw 'TestMode needs an unprivileged bridge port.' }
if ($BridgePort -ne 0 -and ($BridgePort -lt 1024 -or $BridgePort -gt 65535)) { throw 'Use bridge port 0 or 1024 through 65535.' }
if ($Headless -and $BridgePort -eq 0) {throw 'Headless verification needs a test bridge port.'}
if (!$SessionDirectory) {
    $SessionDirectory = Join-Path $repo ('../../outputs/own-client/sessions/' + [guid]::NewGuid().ToString('N'))
}
$SessionDirectory = [IO.Path]::GetFullPath($SessionDirectory)
if (Test-Path -LiteralPath $SessionDirectory) { throw 'Choose a fresh session directory; existing worlds and caches are never overwritten.' }
New-Item -ItemType Directory -Path $SessionDirectory | Out-Null
@('onboardAccessibility:false','pauseOnLostFocus:false','narrator:0','renderDistance:3','simulationDistance:5','maxFps:60') |
    Set-Content -LiteralPath (Join-Path $SessionDirectory 'options.txt')
$env:JAVA_HOME = (Resolve-Path (Join-Path $tools 'jdk-21.0.8+9')).Path
$runtimeName=if($DatasetOnly){'dataset-runtime'}else{'own-client-runtime'}
$runtime = Join-Path $tools $runtimeName
$build = Join-Path $runtime 'bridge'
$env:GRADLE_USER_HOME = Join-Path $runtime 'gradle-home'
New-Item -ItemType Directory -Force -Path $build, "$env:GRADLE_USER_HOME/caches" | Out-Null
if (!(Test-Path "$env:GRADLE_USER_HOME/caches/fabric-loom/1.21.4/minecraft-merged.jar")) {
    $cached = Join-Path $tools 'player-training-runtime/guest/gradle-home/caches'
    robocopy $cached "$env:GRADLE_USER_HOME/caches" /E /XF '*.lock' /R:0 /W:0 /NFL /NDL /NJH /NJS | Out-Null
    if ($LASTEXITCODE -ge 8) {throw 'Could not prepare isolated navigation build cache'}
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'build.gradle'),(Join-Path $PSScriptRoot 'settings.gradle') -Destination $build -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'src') -Destination $build -Recurse -Force
$arguments = @('--offline','--console=plain','runClient','-x','downloadAssets',"-PnavigationJar=$jar","-PnavigationRunDir=$SessionDirectory")
if(!$DatasetOnly){$arguments += @("-PpolicyWorker=$worker","-PpolicyCheckpoint=$CheckpointPath")}
else {
    $arguments += '-PdatasetMode=1'
    $arguments += @("-PdatasetParticipant=$ParticipantId","-PdatasetParticipantSplit=$ParticipantSplit")
    if($ArenaSetup){$arguments += "-ParenaSetup=$ArenaSetup"}
    if($DatasetOutput){$arguments += ('-PdatasetOutput='+[IO.Path]::GetFullPath($DatasetOutput))}
}
if ($JavaUsername) {$arguments += "-PnavigationUsername=$JavaUsername"}
if ($ConnectLocalPort -ne 0) {$arguments += "-PnavigationConnectPort=$ConnectLocalPort"}
if ($BridgePort -ne 0) { $arguments += "-PbridgePort=$BridgePort" }
if ($TestMode) { $arguments += '-PnavigationTest=1' }
if ($FlatTestWorld) { $arguments += '-PnavigationDemo=1' }
if ($Headless) { $arguments += ('-PheadlessAgent=' + (Join-Path $tools 'headless-agent.jar')) }
Write-Host "Own-client navigation profile: $SessionDirectory"
if(!$DatasetOnly){Write-Host 'Local tasks: !terminator fight USERNAME | go to X Y Z | stop | resume | F8 cancels'}
if(!$DatasetOnly){Write-Host "Frozen combat checkpoint: $CheckpointPath"}
else{Write-Host 'Dataset mode: no combat model or optimizer is started. Use !terminator record start LABEL / record stop.'}
& (Join-Path $tools 'gradle-8.12.1/bin/gradle.bat') -p $build @arguments
if ($LASTEXITCODE -ne 0) { throw "Navigation client exited with code $LASTEXITCODE" }
