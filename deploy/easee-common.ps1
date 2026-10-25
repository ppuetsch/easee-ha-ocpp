#Requires -Version 5.1
# Gemeinsame Konstanten und HTTP-Helfer fuer activate-/deactivate-easee-ocpp.ps1.
# Wird per Dot-Sourcing eingebunden. Quelle API: https://developer.easee.com/docs/ocpp-commissioning-easee-users.md

$EASEE_API_BASE_URL = 'https://api.easee.com'
$EASEE_LOGIN_PATH = '/api/accounts/login'
$EASEE_LOCAL_OCPP_PATH = '/local-ocpp/v1'
$EASEE_CONNECTION_DETAILS_PATH = "$EASEE_LOCAL_OCPP_PATH/connection-details"
$EASEE_APPLY_CONNECTION_PATH = "$EASEE_LOCAL_OCPP_PATH/connections/chargers"
$CONNECTIVITY_MODE_DUAL_PROTOCOL = 'DualProtocol'
$CONNECTIVITY_MODE_OCPP_OFF = 'OcppOff'
$OCPP_ENDPOINT_PATH = '/ocpp'
$JSON_CONTENT_TYPE = 'application/json'
$EASEE_USERNAME_VARIABLE = 'EASEE_USERNAME'
$EASEE_PASSWORD_VARIABLE = 'EASEE_PASSWORD'
$OCPP_BASIC_AUTH_PASSWORD_VARIABLE = 'OCPP_BASIC_AUTH_PASSWORD'
$REDACTED_PLACEHOLDER = '<redacted>'

function Get-EaseeUrl([string] $Path) {
    return "$EASEE_API_BASE_URL$Path"
}

function Get-UserSecret([string] $VariableName) {
    $value = [Environment]::GetEnvironmentVariable($VariableName, 'User')
    if ([string]::IsNullOrEmpty($value)) {
        throw "Umgebungsvariable $VariableName fehlt (nur Name ausgegeben)."
    }
    return $value
}

function Write-DryRunRequest([string] $Method, [string] $Path, [string] $Description) {
    Write-Host "[DryRun] $Method $(Get-EaseeUrl $Path)  $Description"
}

function Invoke-EaseeRequest([string] $Method, [string] $Path, [string] $Token, [hashtable] $Body, [string] $Description) {
    if ($DryRun) {
        Write-DryRunRequest $Method $Path $Description
        return $null
    }
    $parameters = @{
        Method      = $Method
        Uri         = (Get-EaseeUrl $Path)
    }
    if (-not [string]::IsNullOrEmpty($Token)) {
        $parameters.Headers = @{ Authorization = "Bearer $Token" }
    }
    if ($null -ne $Body) {
        $parameters.ContentType = $JSON_CONTENT_TYPE
        $parameters.Body = ($Body | ConvertTo-Json -Depth 10)
    }
    return Invoke-RestMethod @parameters
}

function Connect-Easee {
    if ($DryRun) {
        Write-DryRunRequest 'POST' $EASEE_LOGIN_PATH "Body: userName=`$EASEE_USERNAME, password=`$EASEE_PASSWORD (Werte nicht gelesen)"
        return $REDACTED_PLACEHOLDER
    }
    $body = @{
        userName = (Get-UserSecret $EASEE_USERNAME_VARIABLE)
        password = (Get-UserSecret $EASEE_PASSWORD_VARIABLE)
    }
    $response = Invoke-EaseeRequest 'POST' $EASEE_LOGIN_PATH $null $body 'Login'
    return [string]$response.accessToken
}
