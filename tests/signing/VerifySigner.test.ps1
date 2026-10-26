$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
. (Join-Path $root 'scripts/assert-apk-signer.ps1')
$fingerprint=(Get-Content -Raw (Join-Path $root 'signing/release-signing.json')|ConvertFrom-Json).certificateSha256
$count=0
foreach($prefix in @('Signer #1','V3.0 Signer:','V3.1 Signer:')){
    $details=@('Verifies','Number of signers: 1',"$prefix certificate SHA-256 digest: $($fingerprint.ToLowerInvariant())")
    if((Assert-ApkSigner $details $fingerprint) -ne $fingerprint){throw 'Pinned signer was not recognized.'};$count++
}
$mixed=@('Number of signers: 1',"V3.0 Signer: certificate SHA-256 digest: $fingerprint","V3.1 Signer: certificate SHA-256 digest: $fingerprint")
Assert-ApkSigner $mixed $fingerprint|Out-Null;$count++
$wrong='0'*64
foreach($case in @(
    @{Details=@('Number of signers: 1',"Signer #1 certificate SHA-256 digest: $wrong");Reason='*differs*'}
    @{Details=@('Number of signers: 2',"V3.0 Signer: certificate SHA-256 digest: $fingerprint");Reason='*exactly one*'}
    @{Details=@('Number of signers: 1',"Signer #1 public key SHA-256 digest: $fingerprint");Reason='*missing*'}
    @{Details=@('Number of signers: 1','V3.0 Signer: certificate SHA-256 digest: broken');Reason='*differs*'}
    @{Details=@('Number of signers: 1',"V3.0 Signer: certificate SHA-256 digest: $fingerprint","V3.1 Signer: certificate SHA-256 digest: $wrong");Reason='*differs*'}
    @{Details=@("Signer #1 certificate SHA-256 digest: $fingerprint");Reason='*exactly one*'}
)){
    $reason='';try{Assert-ApkSigner $case.Details $fingerprint|Out-Null}catch{$reason=$_.Exception.Message}
    if($reason -notlike $case.Reason){throw "Invalid signer output was accepted or rejected for the wrong reason: $reason"};$count++
}
Write-Output "PASS: $count APK signer output compatibility and rejection checks"
