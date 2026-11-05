param([ValidateSet('all','protocol','watch')][string]$Area='all')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
function Checked([string]$exe,[string[]]$arguments){& $exe @arguments;if($LASTEXITCODE -ne 0){throw "$exe failed ($LASTEXITCODE)"}}
Push-Location $root
try{
 if($Area -ne 'watch'){
  & (Join-Path $root 'tests/signing/VerifySigner.test.ps1')
  if($env:GITHUB_ACTIONS -ne 'true'){throw 'Executable regressions are cloud-only.'}
  if($IsWindows){Checked (Join-Path $root 'gradlew.bat') @('--no-daemon','-p','core-tests','protocolRegression')}
  else{Checked 'bash' @('./gradlew','--no-daemon','-p','core-tests','protocolRegression')}
 }
 if($Area -ne 'protocol'){
  Checked 'node' @('watch/tests/controller.test.mjs')
  Checked 'node' @('watch/tests/hold.test.mjs')
  Checked 'node' @('watch/tests/display.test.mjs')
  Checked 'node' @('watch/tests/layout.test.mjs')
  Checked 'node' @('watch/tests/compiled.test.mjs')
 }
}finally{Pop-Location}
