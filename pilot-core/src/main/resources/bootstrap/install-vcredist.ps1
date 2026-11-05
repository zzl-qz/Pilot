param([Parameter(Mandatory=$true)][string]$Installer)
$ErrorActionPreference = 'Stop'
try {
    $signature = Get-AuthenticodeSignature -FilePath $Installer
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation(,|$)') {
        throw 'Microsoft installer signature could not be verified. Nothing was installed.'
    }
    # Microsoft may request UAC; never disable UAC or restart Windows automatically.
    $process = Start-Process -FilePath $Installer -ArgumentList @('/install','/passive','/norestart') -PassThru -Wait
    exit $process.ExitCode
} catch {
    Write-Error 'VC runtime installation was not completed. Administrator approval or local policy may be required.'
    exit 1
}
