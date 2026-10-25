#Requires -Version 5.1
<#
.SYNOPSIS
    Deaktiviert die Direct-OCPP-Verbindung eines Easee-Chargers (connectivityMode OcppOff).

.DESCRIPTION
    Ablauf laut Easee-Doku, Option 2: Login, aktuelle connection-details lesen, nur
    connectivityMode auf OcppOff setzen (alle anderen Felder unveraendert), speichern,
    anwenden und zuruecklesen. Der Charger faellt danach auf die Easee-Cloud zurueck.
    Secrets werden nie ausgegeben. Mit -DryRun werden nur die Schritte gezeigt.

.PARAMETER Serial
    Seriennummer des Chargers (entspricht der ChargePointId).

.PARAMETER DryRun
    Zeigt die Aufrufe, fuehrt keine HTTP-Anfragen aus und liest keine Secrets.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'easee-common.ps1')

function Get-CurrentConnectionDetails([string] $Token) {
    $path = "$EASEE_CONNECTION_DETAILS_PATH/$Serial"
    $details = Invoke-EaseeRequest 'GET' $path $Token $null 'Aktuelle Verbindungsdaten lesen'
    if ($DryRun) { return @{} }
    return $details
}

function New-OcppOffBody($CurrentDetails) {
    if ($DryRun) {
        return @{ connectivityMode = $CONNECTIVITY_MODE_OCPP_OFF }
    }
    $body = @{
        connectivityMode        = $CONNECTIVITY_MODE_OCPP_OFF
        websocketConnectionArgs = $CurrentDetails.websocketConnectionArgs
        chargePointId           = $CurrentDetails.chargePointId
    }
    if ($null -ne $CurrentDetails.basicAuthPassword) {
        $body.basicAuthPassword = $CurrentDetails.basicAuthPassword
    }
    return $body
}

function Save-OcppOffDetails([string] $Token, $CurrentDetails) {
    $body = New-OcppOffBody $CurrentDetails
    $path = "$EASEE_CONNECTION_DETAILS_PATH/$Serial"
    $response = Invoke-EaseeRequest 'POST' $path $Token $body 'Verbindungsdaten speichern (OcppOff)'
    if ($DryRun) { return '<version>' }
    return [string]$response.version
}

function Apply-OcppOffDetails([string] $Token, [string] $Version) {
    $path = "$EASEE_APPLY_CONNECTION_PATH/$Serial"
    Invoke-EaseeRequest 'POST' $path $Token @{ version = $Version } 'Konfiguration anwenden' | Out-Null
}

function Show-DeactivationState([string] $Token) {
    $path = "$EASEE_CONNECTION_DETAILS_PATH/$Serial"
    $state = Invoke-EaseeRequest 'GET' $path $Token $null 'Konfiguration zuruecklesen'
    if ($DryRun) { return }
    Write-Host "connectivityMode: $($state.connectivityMode)"
    Write-Host "chargePointId:    $($state.chargePointId)"
}

function Invoke-Deactivation {
    $token = Connect-Easee
    $currentDetails = Get-CurrentConnectionDetails $token
    $version = Save-OcppOffDetails $token $currentDetails
    Apply-OcppOffDetails $token $version
    Show-DeactivationState $token
}

Invoke-Deactivation
