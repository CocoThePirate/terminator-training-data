param([string]$PortTools,[string]$OutputJar)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$tools=(Resolve-Path (Join-Path $repo '../toolchains')).Path
if(!$PortTools){$PortTools=Join-Path $tools 'data-port-26.2'}
$PortTools=(Resolve-Path $PortTools).Path
if(!$OutputJar){$OutputJar=Join-Path $repo '../../Training-Data/26.2/terminator-arena-26.2.jar'}
$classes=Join-Path $PortTools ('build/'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes,(Split-Path ([IO.Path]::GetFullPath($OutputJar))) | Out-Null
$mixin=Get-ChildItem "$PortTools/server-runtime/libraries/net/fabricmc/sponge-mixin" -Recurse -Filter *.jar | Select-Object -First 1 -ExpandProperty FullName
if(!$mixin){throw 'Prepare the 26.2 Fabric server dependencies first.'}
$libraries=@(Get-ChildItem "$PortTools/libraries" -Recurse -Filter *.jar | Select-Object -ExpandProperty FullName)
$classpath=(@("$PortTools/client.jar",$mixin)+$libraries) -join ';'
$sources=@(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter *.java | Select-Object -ExpandProperty FullName)
& "$tools/jdk-25.0.5.0.2+1/bin/javac.exe" -proc:none --release 25 -cp $classpath -d $classes @sources
if($LASTEXITCODE -ne 0){throw '26.2 adapter compilation failed'}
Copy-Item "$PSScriptRoot/resources/*" $classes -Force
Copy-Item "$repo/training/scenarios/catalog.json" "$classes/terminator-catalog.json"
& "$tools/jdk-25.0.5.0.2+1/bin/jar.exe" --create --file $OutputJar -C $classes .
if($LASTEXITCODE -ne 0){throw '26.2 adapter packaging failed'}
Write-Output "Built $OutputJar"
