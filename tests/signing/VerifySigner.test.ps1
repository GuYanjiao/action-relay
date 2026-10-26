$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
. (Join-Path $root 'scripts/assert-apk-signer.ps1')
$fingerprint=(Get-Content -Raw (Join-Path $root 'signing/release-signing.json')|ConvertFrom-Json).certificateSha256
$count=0
foreach($prefix in @('Signer #1','V3.0 Signer','V3.1 Signer')){
    $details=@('Verifies','Number of signers: 1',"${prefix}: certificate SHA-256 digest: $($fingerprint.ToLowerInvariant())")
    if((Assert-ApkSigner $details $fingerprint) -ne $fingerprint){throw 'Pinned signer was not recognized.'};$count++
}
$mixed=@('Number of signers: 1',"V3.0 Signer: certificate SHA-256 digest: $fingerprint","V3.1 Signer: certificate SHA-256 digest: $fingerprint")
Assert-ApkSigner $mixed $fingerprint|Out-Null;$count++
$wrong='0'*64
foreach($details in @(
    ,@('Number of signers: 1',"Signer #1: certificate SHA-256 digest: $wrong"),
    ,@('Number of signers: 2',"V3.0 Signer: certificate SHA-256 digest: $fingerprint"),
    ,@('Number of signers: 1',"Signer #1: public key SHA-256 digest: $fingerprint"),
    ,@('Number of signers: 1','V3.0 Signer: certificate SHA-256 digest: broken'),
    ,@('Number of signers: 1',"V3.0 Signer: certificate SHA-256 digest: $fingerprint","V3.1 Signer: certificate SHA-256 digest: $wrong"),
    ,@("Signer #1: certificate SHA-256 digest: $fingerprint")
)){
    $rejected=$false;try{Assert-ApkSigner $details $fingerprint|Out-Null}catch{$rejected=$true}
    if(-not $rejected){throw 'Invalid signer output was accepted.'};$count++
}
Write-Output "PASS: $count APK signer output compatibility and rejection checks"
