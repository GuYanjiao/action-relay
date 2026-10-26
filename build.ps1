param(
  [string]$AndroidPlatform,
  [string]$BuildTools,
  [string]$JdkBin,
  [string]$AiotCli,
  [switch]$SkipWatch,
  [string]$VersionName = '0.3.1',
  [string]$SigningStorePassword = $env:ACTION_RELAY_STORE_PASSWORD,
  [string]$SigningKeyPassword = $env:ACTION_RELAY_KEY_PASSWORD
)
$ErrorActionPreference='Stop'
$projectRoot=$PSScriptRoot
if (-not $SigningStorePassword) { $SigningStorePassword = 'android' }
if (-not $SigningKeyPassword) { $SigningKeyPassword = $SigningStorePassword }
if(-not $JdkBin){$JdkBin=Join-Path $env:JAVA_HOME 'bin'}
if(-not (Test-Path -LiteralPath (Join-Path $AndroidPlatform 'android.jar'))){throw 'Pass -AndroidPlatform with android.jar (API 36).'}
if(-not (Test-Path -LiteralPath (Join-Path $BuildTools 'aapt2.exe'))){throw 'Pass -BuildTools with Android SDK build tools 36.0.0.'}
function Run-Checked([string]$program,[string[]]$arguments){& $program @arguments;if($LASTEXITCODE -ne 0){throw "$program failed ($LASTEXITCODE)"}}
$build=Join-Path $projectRoot 'android\build'
$sign=Join-Path $projectRoot '.signing'
New-Item -ItemType Directory -Force -Path $build,"$build\classes","$build\generated","$build\dex",$sign | Out-Null
$keystore=Join-Path $sign 'actionrelay.p12'
if(-not (Test-Path -LiteralPath $keystore)){
  Run-Checked "$JdkBin\keytool.exe" @('-genkeypair','-alias','actionrelay','-keyalg','RSA','-keysize','2048','-validity','3650','-sigalg','SHA256withRSA','-dname','CN=Action Relay Personal Test','-storetype','PKCS12','-keystore',$keystore,'-storepass',$SigningStorePassword,'-keypass',$SigningKeyPassword,'-noprompt')
}
Run-Checked "$JdkBin\javac.exe" @('-encoding','UTF-8','-d',$sign,(Join-Path $projectRoot 'ExportSigning.java'))
$watchSign=Join-Path $projectRoot 'watch\sign\debug'
Run-Checked "$JdkBin\java.exe" @('-cp',$sign,'ExportSigning',$keystore,$SigningStorePassword,$watchSign)
$androidJar=Join-Path $AndroidPlatform 'android.jar'
$resources=Join-Path $build 'resources.zip'
Run-Checked "$BuildTools\aapt2.exe" @('compile','--dir',(Join-Path $projectRoot 'android\res'),'-o',$resources)
Run-Checked "$BuildTools\aapt2.exe" @('link','-I',$androidJar,'--manifest',(Join-Path $projectRoot 'android\AndroidManifest.xml'),'--java',"$build\generated",'-o',"$build\resources.apk",'--min-sdk-version','31','--target-sdk-version','36',$resources)
$jars=@(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'android\libs') -Filter '*.jar' -File | ForEach-Object {$_.FullName})
$javaSources=@(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'android\src'),"$build\generated" -Recurse -Filter '*.java' -File | ForEach-Object {$_.FullName})
$sourceList=Join-Path $build 'sources.txt'
$sourceText=($javaSources|ForEach-Object {'"'+$_.Replace('\','/')+'"'}) -join "`n"
[System.IO.File]::WriteAllText($sourceList,$sourceText,[System.Text.UTF8Encoding]::new($false))
$classPath=(@($androidJar)+$jars)-join ';'
Run-Checked "$JdkBin\javac.exe" @('--release','8','-encoding','UTF-8','-cp',$classPath,'-d',"$build\classes",('@'+$sourceList))
Run-Checked "$JdkBin\jar.exe" @('cf',"$build\classes.jar",'-C',"$build\classes",'.')
$dexArgs=@('-cp',"$BuildTools\lib\d8.jar",'com.android.tools.r8.D8','--release','--min-api','31','--lib',$androidJar,'--output',"$build\dex","$build\classes.jar")+$jars
Run-Checked "$JdkBin\java.exe" $dexArgs
Copy-Item -LiteralPath "$build\resources.apk" -Destination "$build\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[System.IO.Compression.ZipFile]::Open("$build\unsigned.apk",[System.IO.Compression.ZipArchiveMode]::Update)
try{Get-ChildItem -LiteralPath "$build\dex" -Filter '*.dex' -File|ForEach-Object {[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$_.FullName,$_.Name,[System.IO.Compression.CompressionLevel]::Optimal)|Out-Null}}finally{$archive.Dispose()}
Run-Checked "$BuildTools\zipalign.exe" @('-f','4',"$build\unsigned.apk","$build\aligned.apk")
$apk=Join-Path $build ("ActionRelay-$VersionName.apk")
Run-Checked "$JdkBin\java.exe" @('-jar',"$BuildTools\lib\apksigner.jar",'sign','--ks',$keystore,'--ks-key-alias','actionrelay','--ks-pass',"pass:$SigningStorePassword",'--key-pass',"pass:$SigningKeyPassword",'--out',$apk,"$build\aligned.apk")
Run-Checked "$JdkBin\java.exe" @('-jar',"$BuildTools\lib\apksigner.jar",'verify','--verbose','--print-certs',$apk)
if(-not $SkipWatch){
  if(-not (Test-Path -LiteralPath $AiotCli)){throw 'Pass -AiotCli (aiot-toolkit lib/bin.js) for the watch build.'}
  Push-Location (Join-Path $projectRoot 'watch')
  try{Run-Checked 'node' @($AiotCli,'build')}finally{Pop-Location}
}
Write-Output "Built $apk"
