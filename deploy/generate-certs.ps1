#Requires -Version 5.1
<#
.SYNOPSIS
    Erzeugt eine eigene CA und ein Serverzertifikat fuer den OCPP-Endpunkt (wss, Port 8443).

.DESCRIPTION
    Schreibt keystore.p12 (PKCS12, Server-Key + Kette) und ca.pem nach -OutputDir.
    Der CA-Keystore liegt getrennt in -CaDirectory (ausserhalb des Repos). Er wird nur
    angelegt, wenn er fehlt; ein Aufruf mit -Force erneuert also nur das Serverzertifikat.

    Laufzeiten: CA 10 Jahre, Server 2 Jahre. Spaetestens 30 Tage vor Ablauf des
    Serverzertifikats (mit -Verify pruefen) mit -Force neu erzeugen und danach
    deploy.ps1 und activate-easee-ocpp.ps1 erneut ausfuehren.

    Passwort: User-Umgebungsvariable KEYSTORE_PASSWORD. Fehlt sie, wird ein Passwort erzeugt
    und als User-Umgebungsvariable gespeichert (der Wert erscheint nicht in der Ausgabe).
    Existiert die CA bereits und fehlt die Variable, bricht das Skript ab, weil der
    CA-Keystore nur mit dem bisherigen Passwort geoeffnet werden kann.

.PARAMETER OutputDir
    Zielverzeichnis fuer keystore.p12 und ca.pem (Standard: <Repo>/secrets).

.PARAMETER CaDirectory
    Verzeichnis fuer den CA-Keystore (Standard: %USERPROFILE%\easee-ha-ocpp-ca).

.PARAMETER CommonName
    Hostname des Servers (Standard: docker.fritz.box).

.PARAMETER AdditionalSans
    Zusaetzliche Subject-Alternative-Names. IPv4-Adressen werden als ip:, alle anderen als dns: eingetragen.

.PARAMETER Force
    Vorhandene keystore.p12 und ca.pem im Zielverzeichnis ueberschreiben.

.PARAMETER Verify
    Nur pruefen: zeigt keytool -list der vorhandenen Dateien, erzeugt nichts.
#>
[CmdletBinding()]
param(
    [string] $OutputDir = '',
    [string] $CaDirectory = (Join-Path $env:USERPROFILE 'easee-ha-ocpp-ca'),
    [string] $CommonName = 'docker.fritz.box',
    [string[]] $AdditionalSans = @(),
    [switch] $Force,
    [switch] $Verify
)

$ErrorActionPreference = 'Stop'

if (-not $OutputDir) { $OutputDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'secrets' }

# Laufzeiten und Schluessel-Parameter. Zertifikats-Vorgaben des Chargers sind undokumentiert (Risiko R5).
$CA_VALIDITY_DAYS = 3650
$SERVER_VALIDITY_DAYS = 730
$KEY_ALGORITHM = 'RSA'
$KEY_SIZE_BITS = 2048
$CA_ALIAS = 'easee-ha-ocpp-ca'
$SERVER_ALIAS = 'easee-ha-ocpp'
$CA_KEYSTORE_FILE_NAME = 'ca.p12'
$SERVER_KEYSTORE_FILE_NAME = 'keystore.p12'
$CA_PEM_FILE_NAME = 'ca.pem'
$PASSWORD_SOURCE_VARIABLE = 'KEYSTORE_PASSWORD'
$KEYTOOL_PASSWORD_VARIABLE = 'EASEE_HA_OCPP_KEYTOOL_PASSWORD'
$GENERATED_PASSWORD_LENGTH = 32
$PASSWORD_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
$IPV4_PATTERN = '^\d{1,3}(\.\d{1,3}){3}$'

function Assert-KeytoolAvailable {
    if ($null -eq (Get-Command keytool -ErrorAction SilentlyContinue)) {
        throw 'keytool nicht gefunden. JDK 25 installieren und in PATH aufnehmen.'
    }
}

function New-RandomPassword {
    $bytes = New-Object byte[] $GENERATED_PASSWORD_LENGTH
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $characters = foreach ($byte in $bytes) { $PASSWORD_ALPHABET[$byte % $PASSWORD_ALPHABET.Length] }
    return -join $characters
}

function Get-ConfiguredPassword {
    return [Environment]::GetEnvironmentVariable($PASSWORD_SOURCE_VARIABLE, 'User')
}

function Assert-NoExistingCaWithoutPassword {
    $caKeystorePath = Join-Path $CaDirectory $CA_KEYSTORE_FILE_NAME
    if (Test-Path -LiteralPath $caKeystorePath) {
        throw "KEYSTORE_PASSWORD fehlt, die CA $caKeystorePath existiert aber bereits und laesst sich nur mit dem bisherigen Passwort oeffnen."
    }
}

function Save-GeneratedPassword([string] $Password) {
    [Environment]::SetEnvironmentVariable($PASSWORD_SOURCE_VARIABLE, $Password, 'User')
    Write-Host "KEYSTORE_PASSWORD war nicht gesetzt: Passwort erzeugt und als User-Umgebungsvariable gespeichert."
    Write-Host "Der Wert wird aus Sicherheitsgruenden nicht ausgegeben. Ein neues Terminal oeffnen, damit er wirksam ist."
}

function Resolve-IssuancePassword {
    $configured = Get-ConfiguredPassword
    if (-not [string]::IsNullOrEmpty($configured)) { return $configured }
    Assert-NoExistingCaWithoutPassword
    $generated = New-RandomPassword
    Save-GeneratedPassword $generated
    return $generated
}

function Resolve-VerificationPassword {
    $configured = Get-ConfiguredPassword
    if ([string]::IsNullOrEmpty($configured)) {
        throw 'KEYSTORE_PASSWORD ist nicht gesetzt; zum Pruefen wird das Keystore-Passwort benoetigt.'
    }
    return $configured
}

function Invoke-WithKeytoolPassword([string] $Password, [scriptblock] $Action) {
    [Environment]::SetEnvironmentVariable($KEYTOOL_PASSWORD_VARIABLE, $Password, 'Process')
    try {
        & $Action
    }
    finally {
        [Environment]::SetEnvironmentVariable($KEYTOOL_PASSWORD_VARIABLE, $null, 'Process')
    }
}

function Get-PasswordArguments {
    return @('-storepass:env', $KEYTOOL_PASSWORD_VARIABLE)
}

function Invoke-Keytool([string[]] $KeytoolArguments) {
    $operation = $KeytoolArguments[0]
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & keytool @KeytoolArguments 2>&1
    }
    finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($LASTEXITCODE -ne 0) {
        throw "keytool $operation fehlgeschlagen (Exit $LASTEXITCODE): $($output -join ' ')"
    }
}

function Get-SubjectAlternativeNameValue {
    $entries = @("dns:$CommonName")
    foreach ($entry in $AdditionalSans) {
        if ($entry -match $IPV4_PATTERN) { $entries += "ip:$entry" } else { $entries += "dns:$entry" }
    }
    return 'san=' + ($entries -join ',')
}

function New-CertificateAuthority([string] $CaKeystorePath) {
    if (Test-Path -LiteralPath $CaKeystorePath) {
        Write-Host "CA vorhanden, wird wiederverwendet: $CaKeystorePath"
        return
    }
    $arguments = @(
        '-genkeypair', '-alias', $CA_ALIAS,
        '-keyalg', $KEY_ALGORITHM, '-keysize', $KEY_SIZE_BITS,
        '-dname', 'CN=easee-ha-ocpp CA',
        '-ext', 'bc:critical=ca:true',
        '-ext', 'ku:critical=keyCertSign,cRLSign',
        '-validity', $CA_VALIDITY_DAYS,
        '-keystore', $CaKeystorePath, '-storetype', 'PKCS12',
        '-keypass:env', $KEYTOOL_PASSWORD_VARIABLE
    ) + (Get-PasswordArguments)
    Invoke-Keytool $arguments
    Write-Host "CA neu erzeugt: $CaKeystorePath"
}

function New-ServerKeystoreWithRequest([string] $TempKeystorePath, [string] $RequestPath) {
    $generateArguments = @(
        '-genkeypair', '-alias', $SERVER_ALIAS,
        '-keyalg', $KEY_ALGORITHM, '-keysize', $KEY_SIZE_BITS,
        '-dname', "CN=$CommonName",
        '-keystore', $TempKeystorePath, '-storetype', 'PKCS12',
        '-keypass:env', $KEYTOOL_PASSWORD_VARIABLE
    ) + (Get-PasswordArguments)
    Invoke-Keytool $generateArguments
    $requestArguments = @(
        '-certreq', '-alias', $SERVER_ALIAS,
        '-keystore', $TempKeystorePath, '-file', $RequestPath
    ) + (Get-PasswordArguments)
    Invoke-Keytool $requestArguments
}

function Invoke-ServerCertificateSigning([string] $CaKeystorePath, [string] $RequestPath, [string] $CertificatePath) {
    $signArguments = @(
        '-gencert', '-rfc',
        '-infile', $RequestPath, '-outfile', $CertificatePath,
        '-alias', $CA_ALIAS, '-keystore', $CaKeystorePath, '-storetype', 'PKCS12',
        '-validity', $SERVER_VALIDITY_DAYS,
        '-ext', (Get-SubjectAlternativeNameValue),
        '-ext', 'eku=serverAuth',
        '-ext', 'ku=digitalSignature,keyEncipherment',
        '-ext', 'bc=ca:false'
    ) + (Get-PasswordArguments)
    Invoke-Keytool $signArguments
}

function Export-CaCertificatePem([string] $CaKeystorePath, [string] $PemPath) {
    Invoke-Keytool (@('-exportcert', '-rfc', '-alias', $CA_ALIAS, '-keystore', $CaKeystorePath, '-storetype', 'PKCS12', '-file', $PemPath) + (Get-PasswordArguments))
}

function Import-ChainIntoKeystore([string] $TempKeystorePath, [string] $CaPemPath, [string] $CertificatePath) {
    Invoke-Keytool (@('-importcert', '-noprompt', '-alias', $CA_ALIAS, '-file', $CaPemPath, '-keystore', $TempKeystorePath, '-storetype', 'PKCS12') + (Get-PasswordArguments))
    Invoke-Keytool (@('-importcert', '-noprompt', '-alias', $SERVER_ALIAS, '-file', $CertificatePath, '-keystore', $TempKeystorePath, '-storetype', 'PKCS12') + (Get-PasswordArguments))
}

function Assert-OutputOverwriteAllowed([string] $KeystorePath, [string] $PemPath) {
    if ($Force) { return }
    if ((Test-Path -LiteralPath $KeystorePath) -or (Test-Path -LiteralPath $PemPath)) {
        throw "Zieldateien existieren bereits in $OutputDir. Mit -Force ueberschreiben."
    }
}

function Invoke-IssuanceSteps([string] $Password, [string] $WorkDir, [string] $KeystorePath, [string] $PemPath) {
    $caKeystorePath = Join-Path $CaDirectory $CA_KEYSTORE_FILE_NAME
    $tempKeystorePath = Join-Path $WorkDir $SERVER_KEYSTORE_FILE_NAME
    $requestPath = Join-Path $WorkDir 'server.csr'
    $certificatePath = Join-Path $WorkDir 'server.pem'
    $caPemTempPath = Join-Path $WorkDir $CA_PEM_FILE_NAME
    Invoke-WithKeytoolPassword $Password {
        New-CertificateAuthority $caKeystorePath
        New-ServerKeystoreWithRequest $tempKeystorePath $requestPath
        Invoke-ServerCertificateSigning $caKeystorePath $requestPath $certificatePath
        Export-CaCertificatePem $caKeystorePath $caPemTempPath
        Import-ChainIntoKeystore $tempKeystorePath $caPemTempPath $certificatePath
    }
    Move-Item -LiteralPath $tempKeystorePath -Destination $KeystorePath -Force
    Copy-Item -LiteralPath $caPemTempPath -Destination $PemPath -Force
}

function Invoke-CertificateIssuance {
    Assert-KeytoolAvailable
    New-Item -ItemType Directory -Force -Path $OutputDir, $CaDirectory | Out-Null
    $keystorePath = Join-Path $OutputDir $SERVER_KEYSTORE_FILE_NAME
    $pemPath = Join-Path $OutputDir $CA_PEM_FILE_NAME
    Assert-OutputOverwriteAllowed $keystorePath $pemPath
    $password = Resolve-IssuancePassword
    $workDir = Join-Path $env:TEMP ('easee-certs-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $workDir | Out-Null
    try {
        Invoke-IssuanceSteps $password $workDir $keystorePath $pemPath
    }
    finally {
        Remove-Item -LiteralPath $workDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Host "Geschrieben: $keystorePath und $pemPath"
}

function Show-Verification {
    Assert-KeytoolAvailable
    $keystorePath = Join-Path $OutputDir $SERVER_KEYSTORE_FILE_NAME
    $pemPath = Join-Path $OutputDir $CA_PEM_FILE_NAME
    $password = Resolve-VerificationPassword
    Invoke-WithKeytoolPassword $password {
        & keytool -list -v -keystore $keystorePath -storetype PKCS12 -storepass:env $KEYTOOL_PASSWORD_VARIABLE
    }
    & keytool -printcert -file $pemPath
}

if ($Verify) {
    Show-Verification
    return
}
Invoke-CertificateIssuance
Show-Verification
