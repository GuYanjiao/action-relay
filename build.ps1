param(
  [string]$AndroidPlatform,
  [string]$BuildTools,
  [string]$JdkBin,
  [string]$AiotCli,
  [switch]$SkipWatch,
  [string]$VersionName,
  [string]$SigningKeystore = $env:ACTION_RELAY_KEYSTORE,
  [string]$SigningAlias
)
$ErrorActionPreference='Stop'
$projectRoot=$PSScriptRoot
if($env:GITHUB_ACTIONS -ne 'true'){throw 'Package compilation is cloud-only. Push changes and use GitHub Actions.'}
function Remove-GeneratedPath([string]$relative){
  $path=Join-Path $projectRoot $relative
  if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path -Recurse -Force}
}
Remove-GeneratedPath 'android\build'
Remove-GeneratedPath 'watch\build'
Remove-GeneratedPath 'watch\dist'
$watchManifest=Get-Content -Raw (Join-Path $projectRoot 'watch/src/manifest.json')|ConvertFrom-Json
[xml]$androidManifest=Get-Content -Raw (Join-Path $projectRoot 'android/AndroidManifest.xml')
$androidNamespace='http://schemas.android.com/apk/res/android'
if(-not $VersionName){$VersionName=$watchManifest.versionName}
if($VersionName -ne $watchManifest.versionName -or $VersionName -ne $androidManifest.manifest.GetAttribute('versionName',$androidNamespace) -or [int]$watchManifest.versionCode -ne [int]$androidManifest.manifest.GetAttribute('versionCode',$androidNamespace)){throw 'Android/Watch versions must match.'}
$signing=Get-Content -Raw (Join-Path $projectRoot 'signing/release-signing.json')|ConvertFrom-Json
if(-not $SigningAlias){$SigningAlias=$signing.alias}
if($SigningAlias -ne $signing.alias){throw 'Signing alias must match the fixed release configuration.'}
if (-not $SigningKeystore) { throw 'ACTION_RELAY_KEYSTORE is required; refusing to generate or use a fallback keystore.' }
if (-not (Test-Path -LiteralPath $SigningKeystore)) { throw "Signing keystore not found: $SigningKeystore" }
if (-not $env:ACTION_RELAY_STORE_PASSWORD) { throw 'ACTION_RELAY_STORE_PASSWORD is required.' }
if (-not $env:ACTION_RELAY_KEY_PASSWORD) { throw 'ACTION_RELAY_KEY_PASSWORD is required.' }
if(-not $JdkBin){$JdkBin=Join-Path $env:JAVA_HOME 'bin'}
if(-not (Test-Path -LiteralPath (Join-Path $AndroidPlatform 'android.jar'))){throw 'Pass -AndroidPlatform with android.jar (current cloud platform: API 37.2).'}
if(-not (Test-Path -LiteralPath (Join-Path $BuildTools 'aapt2.exe'))){throw 'Pass -BuildTools with Android SDK build tools 37.0.0.'}
function Run-Checked([string]$program,[string[]]$arguments){& $program @arguments;if($LASTEXITCODE -ne 0){throw "$program failed ($LASTEXITCODE)"}}
$build=Join-Path $projectRoot 'android\build'
$sign=Join-Path $projectRoot '.signing'
New-Item -ItemType Directory -Force -Path $build,$sign | Out-Null
Run-Checked "$JdkBin\javac.exe" @('-encoding','UTF-8','-d',$sign,(Join-Path $projectRoot 'ExportSigning.java'))
$watchSign=Join-Path $projectRoot 'watch\sign\debug'
Run-Checked "$JdkBin\java.exe" @('-cp',$sign,'ExportSigning',$SigningKeystore,$SigningAlias,$watchSign,$signing.certificateSha256)
$native=Join-Path $projectRoot 'android/native-libs/arm64-v8a/libaction_relay_core.so'
if(-not (Test-Path -LiteralPath $native)){throw 'Cloud-built ARM64 Rust library is required.'}
$notices=Join-Path $projectRoot 'android/native-assets/native'
New-Item -ItemType Directory -Force -Path $notices | Out-Null
foreach($license in @('Rust-LICENSE-MIT.txt','Rust-LICENSE-APACHE.txt')){
  Copy-Item -LiteralPath (Join-Path $projectRoot "android/native-libs/arm64-v8a/$license") -Destination $notices -Force
}
Run-Checked (Join-Path $projectRoot 'gradlew.bat') @('--no-daemon',':android:assembleRelease')
$apk=Join-Path $build ("ActionRelay-$VersionName.apk")
Copy-Item -LiteralPath (Join-Path $build 'outputs/apk/release/android-release.apk') -Destination $apk -Force
Run-Checked "$JdkBin\java.exe" @('--enable-native-access=ALL-UNNAMED','-jar',"$BuildTools\lib\apksigner.jar",'verify','--verbose','--print-certs',$apk)
Run-Checked "$BuildTools\zipalign.exe" @('-c','-P','16','4',$apk)
if(-not $SkipWatch){
  if(-not (Test-Path -LiteralPath $AiotCli)){throw 'Pass -AiotCli (aiot-toolkit lib/bin.js) for the watch build.'}
  Push-Location (Join-Path $projectRoot 'watch')
  try{Run-Checked 'node' @($AiotCli,'build')}finally{Pop-Location}
}
Write-Output "Built $apk"
