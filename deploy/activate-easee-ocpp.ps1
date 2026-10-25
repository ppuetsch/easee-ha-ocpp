#Requires -Version 5.1
<#
.SYNOPSIS
    Aktiviert die Direct-OCPP-Verbindung eines Easee-Chargers zum easee-ha-ocpp-Server.

.DESCRIPTION
    Ablauf:
      1. Login bei der Easee-API (EASEE_USERNAME / EASEE_PASSWORD).
      2. Circuit-Reset: dynamicCurrent des Circuits auf das Maximum setzen und zuruecklesen
         (der alte Regler hinterlaesst dort ein Limit). Abschaltbar mit -SkipCircuitReset.
      3. connection-details speichern: connectivityMode DualProtocol, wss-URL, CA-Zertifikat
         (ca.pem), CA-Domain, basicAuthPassword (OCPP_BASIC_AUTH_PASSWORD).
      4. Konfiguration anwenden (mit der version aus Schritt 3) und zuruecklesen.
    Secrets werden nie ausgegeben. Mit -DryRun werden nur die Schritte gezeigt.

.PARAMETER Serial
    Seriennummer des Chargers (entspricht der ChargePointId).

.PARAMETER OcppHost
    Hostname des OCPP-Servers (Standard: docker.fritz.box). Muss zum CN/SAN des Zertifikats passen.

.PARAMETER OcppPort
    TLS-Port des OCPP-Servers (Standard: 8443).

.PARAMETER CaCertificatePath
    Pfad zur ca.pem (Standard: <Repo>/secrets/ca.pem).

.PARAMETER SkipCircuitReset
    Ueberspringt den Circuit-Reset.

.PARAMETER DryRun
    Zeigt die Aufrufe, fuehrt keine HTTP-Anfragen aus und liest keine Secrets.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,
    [string] $OcppHost = 'docker.fritz.box',
    [int] $OcppPort = 8443,
    [string] $CaCertificatePath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'secrets\ca.pem'),
    [switch] $SkipCircuitReset,
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'easee-common.ps1')

# Circuit des Standorts, auf dem der Charger haengt (aus dem alten Regler uebernommen).
$CIRCUIT_SITE_ID = 117481
$CIRCUIT_ID = 346122
$CIRCUIT_MAX_CURRENT_PER_PHASE = 32

function Get-OcppWebsocketUrl {
    return "wss://${OcppHost}:${OcppPort}$OCPP_ENDPOINT_PATH"
}

function Get-CaCertificatePem {
    if ($DryRun) { return $REDACTED_PLACEHOLDER }
    if (-not (Test-Path -LiteralPath $CaCertificatePath)) {
        throw "CA-Zertifikat nicht gefunden: $CaCertificatePath (zuerst generate-certs.ps1 ausfuehren)."
    }
    return [System.IO.File]::ReadAllText($CaCertificatePath)
}

function Get-CircuitCurrentPath {
    return "/api/sites/$CIRCUIT_SITE_ID/circuits/$CIRCUIT_ID/dynamicCurrent"
}

function New-MaximumCircuitCurrentBody {
    return @{
        phase1 = $CIRCUIT_MAX_CURRENT_PER_PHASE
        phase2 = $CIRCUIT_MAX_CURRENT_PER_PHASE
        phase3 = $CIRCUIT_MAX_CURRENT_PER_PHASE
    }
}

function Reset-CircuitCurrent([string] $Token) {
    $path = Get-CircuitCurrentPath
    Invoke-EaseeRequest 'POST' $path $Token (New-MaximumCircuitCurrentBody) 'Circuit-Reset auf Maximum' | Out-Null
    $readBack = Invoke-EaseeRequest 'GET' $path $Token $null 'Circuit-Reset zuruecklesen'
    if ($DryRun) { return }
    Write-Host "Circuit-Reset: phase1=$($readBack.phase1) phase2=$($readBack.phase2) phase3=$($readBack.phase3)"
}

function New-ConnectionDetailsBody([string] $CaPem) {
    return @{
        connectivityMode       = $CONNECTIVITY_MODE_DUAL_PROTOCOL
        websocketConnectionArgs = @{
            url                 = (Get-OcppWebsocketUrl)
            caCertificate       = $CaPem
            caCertificateDomain = $OcppHost
        }
        basicAuthPassword      = (Get-BasicAuthPasswordForBody)
    }
}

function Get-BasicAuthPasswordForBody {
    if ($DryRun) { return $REDACTED_PLACEHOLDER }
    return (Get-UserSecret $OCPP_BASIC_AUTH_PASSWORD_VARIABLE)
}

function Save-ConnectionDetails([string] $Token) {
    $body = New-ConnectionDetailsBody (Get-CaCertificatePem)
    $path = "$EASEE_CONNECTION_DETAILS_PATH/$Serial"
    $response = Invoke-EaseeRequest 'POST' $path $Token $body 'Verbindungsdaten speichern (DualProtocol)'
    if ($DryRun) { return $REDACTED_PLACEHOLDER }
    return [string]$response.version
}

function Apply-ConnectionDetails([string] $Token, [string] $Version) {
    $path = "$EASEE_APPLY_CONNECTION_PATH/$Serial"
    Invoke-EaseeRequest 'POST' $path $Token @{ version = $Version } 'Konfiguration anwenden' | Out-Null
}

function Show-ConnectionState([string] $Token) {
    $path = "$EASEE_CONNECTION_DETAILS_PATH/$Serial"
    $state = Invoke-EaseeRequest 'GET' $path $Token $null 'Konfiguration zuruecklesen'
    if ($DryRun) { return }
    Write-Host "connectivityMode: $($state.connectivityMode)"
    Write-Host "url:              $($state.websocketConnectionArgs.url)"
    Write-Host "chargePointId:    $($state.chargePointId)"
}

function Invoke-Activation {
    $token = Connect-Easee
    if (-not $SkipCircuitReset) { Reset-CircuitCurrent $token }
    $version = Save-ConnectionDetails $token
    Apply-ConnectionDetails $token $version
    Show-ConnectionState $token
}

Invoke-Activation
