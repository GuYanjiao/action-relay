$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$sdkRoot=if($env:ANDROID_SDK_ROOT){$env:ANDROID_SDK_ROOT}else{$env:ANDROID_HOME}
$platform=if($sdkRoot){Join-Path $sdkRoot 'platforms\android-36'}else{Join-Path $root '..\android-tools\platform-36_r02\android-36'}
$tools=if($sdkRoot){Join-Path $sdkRoot 'build-tools\36.0.0'}else{Join-Path $root '..\android-tools\build-tools_r36_windows\android-16'}
$jdk=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin'}else{'C:\Program Files\Java\jdk-17\bin'}
$aiot=Join-Path $root 'watch\node_modules\aiot-toolkit\lib\bin.js'
if(-not(Test-Path -LiteralPath $aiot)){$aiot=Join-Path $root '..\s4-probe\node_modules\aiot-toolkit\lib\bin.js'}
& (Join-Path $root 'build.ps1') -AndroidPlatform $platform -BuildTools $tools -JdkBin $jdk -AiotCli $aiot
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
