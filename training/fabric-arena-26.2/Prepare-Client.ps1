param([Parameter(Mandatory=$true)][string]$PortTools)
$ErrorActionPreference='Stop'
$PortTools=(Resolve-Path $PortTools).Path
$metadata=Get-Content (Join-Path $PortTools 'version-metadata.json') -Raw | ConvertFrom-Json
if($metadata.id -ne '26.2'){throw 'Expected official 26.2 metadata'}
$cache=Join-Path $env:APPDATA '.minecraft'
$jobs=[Collections.Generic.List[object]]::new()
foreach($library in $metadata.libraries){
    $allowed=$true
    if($library.rules){$allowed=$false;foreach($rule in $library.rules){if((!$rule.os -or ((!$rule.os.name -or $rule.os.name -eq 'windows') -and (!$rule.os.arch -or $rule.os.arch -eq 'x86_64'))) -and !$rule.features){$allowed=$rule.action -eq 'allow'}}}
    if(!$allowed){continue};$a=$library.downloads.artifact;if(!$a){continue}
    $jobs.Add(@{url=$a.url;hash=$a.sha1;target=(Join-Path $PortTools ('client-libraries/'+$a.path));cached=(Join-Path $cache ('libraries/'+$a.path))})
}
$assetPath=Join-Path $PortTools ('client-assets/indexes/'+$metadata.assetIndex.id+'.json')
New-Item -ItemType Directory -Force -Path (Split-Path $assetPath) | Out-Null
if(!(Test-Path $assetPath)){Invoke-WebRequest $metadata.assetIndex.url -OutFile $assetPath}
if((Get-FileHash $assetPath -Algorithm SHA1).Hash.ToLowerInvariant() -ne $metadata.assetIndex.sha1){throw 'Asset index checksum mismatch'}
$index=Get-Content $assetPath -Raw | ConvertFrom-Json
foreach($entry in $index.objects.PSObject.Properties){$hash=$entry.Value.hash;$relative='objects/'+$hash.Substring(0,2)+'/'+$hash
    $jobs.Add(@{url=('https://resources.download.minecraft.net/'+$hash.Substring(0,2)+'/'+$hash);hash=$hash;target=(Join-Path $PortTools ('client-assets/'+$relative));cached=(Join-Path $cache ('assets/'+$relative))})
}
$jobs | ForEach-Object -Parallel {
    $ErrorActionPreference='Stop';$job=$_
    if(Test-Path -LiteralPath $job.target){if((Get-FileHash -LiteralPath $job.target -Algorithm SHA1).Hash.ToLowerInvariant() -eq $job.hash){return}}
    New-Item -ItemType Directory -Force -Path (Split-Path $job.target) | Out-Null
    if(Test-Path -LiteralPath $job.cached){Copy-Item -LiteralPath $job.cached -Destination $job.target}
    else{for($attempt=0;$attempt -lt 4;$attempt++){try{Invoke-WebRequest $job.url -OutFile $job.target;break}catch{if($attempt -eq 3){throw};Start-Sleep -Milliseconds (500*($attempt+1))}}}
    if((Get-FileHash -LiteralPath $job.target -Algorithm SHA1).Hash.ToLowerInvariant() -ne $job.hash){throw ('Dependency checksum mismatch: '+$job.target)}
} -ThrottleLimit 4
Write-Output "Verified $($jobs.Count) client libraries/assets for 26.2. Existing launcher files were only read."
