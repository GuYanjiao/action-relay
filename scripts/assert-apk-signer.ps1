function Assert-ApkSigner {
    param([string[]]$Details,[string]$ExpectedFingerprint)
    if($ExpectedFingerprint -notmatch '^[a-fA-F0-9]{64}$'){throw 'Invalid pinned signer fingerprint.'}
    $text=$Details -join "`n"
    $counts=[regex]::Matches($text,'(?mi)^Number of signers:\s*(\d+)\s*$')
    if($counts.Count -ne 1 -or $counts[0].Groups[1].Value -ne '1'){throw 'Expected exactly one verified APK signer.'}
    # Build Tools 37 labels schemes as "V3.0 Signer" instead of "Signer #1".
    # Inspect every certificate digest, never a public-key digest or a partial match.
    $lines=@($Details|Where-Object{$_ -match 'certificate SHA-256 digest:'})
    if($lines.Count -eq 0){throw 'APK signer certificate digest is missing.'}
    foreach($line in $lines){
        $match=[regex]::Match($line,'^.+: certificate SHA-256 digest:\s*([a-fA-F0-9]{64})\s*$')
        if(-not $match.Success -or $match.Groups[1].Value.ToUpperInvariant() -ne $ExpectedFingerprint.ToUpperInvariant()){
            throw 'APK signer differs from the fixed certificate or could not be parsed.'
        }
    }
    return $ExpectedFingerprint.ToUpperInvariant()
}
