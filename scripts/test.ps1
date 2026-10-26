param([ValidateSet('all','protocol','watch')][string]$Area='all')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
function Checked([string]$exe,[string[]]$arguments){& $exe @arguments;if($LASTEXITCODE -ne 0){throw "$exe failed ($LASTEXITCODE)"}}
Push-Location $root
try{
 if($Area -ne 'watch'){
  $classes=Join-Path $root 'test-classes';New-Item -ItemType Directory -Force -Path $classes|Out-Null
  $files=@(Get-ChildItem 'android/src/cn/local/actionrelay/protocol' -Filter '*.java' -File|ForEach-Object{$_.FullName})
  $files+=@((Get-Item 'android/src/cn/local/actionrelay/FrameQueue.java').FullName)
  $files+=@((Get-Item 'android/src/cn/local/actionrelay/ReconnectPolicy.java').FullName)
  $files+=@((Get-Item 'android/src/cn/local/actionrelay/WatchOutbox.java').FullName)
  $files+=@((Get-Item 'android/src/cn/local/actionrelay/GpsLifecycle.java').FullName)
  foreach($folder in @('tests/protocol','tests/transport','tests/lifecycle')){$files+=@(Get-ChildItem $folder -Filter '*.java' -Recurse -File|ForEach-Object{$_.FullName})}
  $argFile=Join-Path $classes 'sources.args';[IO.File]::WriteAllText($argFile,(($files|ForEach-Object{'"'+$_.Replace('\','/')+'"'})-join "`n"),[Text.UTF8Encoding]::new($false))
  Checked 'javac' @('--release','8','-encoding','UTF-8','-d',$classes,('@'+$argFile))
  Checked 'java' @('-cp',$classes,'cn.local.actionrelay.protocol.DjiProtocolSelfTest','tests/connect_cmd_frame.txt')
  Checked 'java' @('-cp',$classes,'cn.local.actionrelay.protocol.DjiSessionTransitionTest')
  Checked 'java' @('-cp',$classes,'cn.local.actionrelay.protocol.GpsProtocolTest')
  Checked 'java' @('-cp',$classes,'FrameQueueTest')
  Checked 'java' @('-cp',$classes,'ReconnectPolicyTest')
  Checked 'java' @('-cp',$classes,'WatchOutboxTest')
  Checked 'java' @('-cp',$classes,'cn.local.actionrelay.GpsLifecycleTest')
 }
 if($Area -ne 'protocol'){
  Checked 'node' @('watch/tests/controller.test.mjs')
  Checked 'node' @('watch/tests/compiled.test.mjs')
 }
}finally{Pop-Location}
