#Requires -Version 5.1
<#
.SYNOPSIS
    Deployt easee-ha-ocpp auf den Docker-Host (Standard: docker.fritz.box) per SSH.

.DESCRIPTION
    Holt das Image aus GHCR oder baut es lokal (-Local) und uebertraegt es per
    `docker save | docker load`. Secrets kommen aus den lokalen User-Umgebungsvariablen,
    werden in eine temporaere env-Datei geschrieben, per scp nach ~/easee-ha-ocpp.env
    (Rechte 600) uebertragen und nur ueber `docker run --env-file` uebergeben.
    Der Keystore wird nach ~/easee-secrets/ kopiert (Besitzer uid 10001, Rechte 640).

    Parallelbetrieb: Der Schattenmodus (Standard, ohne -Actuate) sendet nichts an Charger
    und Easee-Cloud und darf daher parallel zum alten Regler pv-surcharge laufen.
    Mit -Actuate ist ein laufender pv-surcharge nur mit -StopLegacy zulaessig.
    -StopLegacy ohne -Actuate stoppt den alten Regler, ohne dass der neue regelt; das wird bestaetigt.

.PARAMETER DryRun
    Zeigt nur die Kommandos an, fuehrt nichts aus, liest keine Secrets und prueft die Keystore-Datei nicht.

.PARAMETER CheckOnly
    Fuehrt nur den Post-Deployment-Check gegen den laufenden Service aus (Version und Health), ohne zu deployen.

.PARAMETER ExpectedVersion
    Erwarteter Short-SHA. Standard: HEAD dieses Repos (mit -Local und uncommitteten Aenderungen mit Suffix -dirty).

.PARAMETER Actuate
    Setzt ACTUATION_ENABLED=true. Standard ist der Schattenmodus (false).

.PARAMETER StopLegacy
    Stoppt und entfernt den alten Container pv-surcharge.

.PARAMETER KeystorePath
    Lokaler Pfad zum Server-Keystore (relativ zum Repo-Root). Standard: secrets/keystore.p12.

.PARAMETER Force
    Unterdrueckt die Bestaetigungsabfrage bei -StopLegacy ohne -Actuate.
#>
[CmdletBinding()]
param(
    [string] $DockerHost = 'docker.fritz.box',
    [string] $SshUser = 'root',
    [string] $Image = 'ghcr.io/ppuetsch/easee-ha-ocpp:latest',
    [string] $KeystorePath = 'secrets/keystore.p12',
    [switch] $Local,
    [switch] $StopLegacy,
    [switch] $DryRun,
    [switch] $Actuate,
    [switch] $Force,
    [switch] $PlainOcpp,
    [string] $ExpectedVersion = '',
    [switch] $CheckOnly
)

$ErrorActionPreference = 'Stop'

$CONTAINER_NAME = 'easee-ha-ocpp'
$LEGACY_CONTAINER_NAME = 'pv-surcharge'
$REMOTE_ENV_FILE_NAME = 'easee-ha-ocpp.env'
$REMOTE_SECRETS_DIR = '~/easee-secrets'
$REMOTE_SECRETS_SCP_DIR = 'easee-secrets'
$KEYSTORE_FILE_NAME = 'keystore.p12'
$CONTAINER_UID = 10001
$KEYSTORE_FILE_MODE = '640'
$SECRETS_DIR_MODE = '750'
$CONFIRMATION_ANSWERS = @('j', 'ja', 'y', 'yes')
$HTTP_PORT = 8080
$OCPP_TLS_PORT = 8443
$DATA_VOLUME = 'easee-data'
$REQUIRED_SECRET_NAMES = @('HA_TOKEN', 'EASEE_USERNAME', 'EASEE_PASSWORD', 'OCPP_BASIC_AUTH_PASSWORD', 'EASEE_OCPP_ALLOWEDIDTAGS')
if (-not $PlainOcpp) { $REQUIRED_SECRET_NAMES += 'KEYSTORE_PASSWORD' }
$PROJECT_ROOT = Split-Path -Parent $PSScriptRoot
$SHORT_SHA_LENGTH = 7
$VERSION_CHECK_TIMEOUT_SECONDS = 120
$VERSION_CHECK_INTERVAL_SECONDS = 3
$VERSION_CHECK_REQUEST_TIMEOUT_SECONDS = 5
$DIRTY_SUFFIX = '-dirty'

function Get-SshTarget {
    return "$SshUser@$DockerHost"
}

function Get-ActuationValue {
    if ($Actuate) { return 'true' }
    return 'false'
}

function Write-DryRunCommand([string] $Text) {
    Write-Host "[DryRun] $Text"
}

function Invoke-Remote([string] $RemoteCommand) {
    $target = Get-SshTarget
    if ($DryRun) {
        Write-DryRunCommand "ssh $target '$RemoteCommand'"
        return ''
    }
    $output = & ssh $target $RemoteCommand
    if ($LASTEXITCODE -ne 0) { throw "Remote-Kommando fehlgeschlagen (Exit $LASTEXITCODE): $RemoteCommand" }
    return $output
}

function Invoke-LocalDocker([string[]] $DockerArguments) {
    if ($DryRun) {
        Write-DryRunCommand ("docker " + ($DockerArguments -join ' '))
        return
    }
    & docker @DockerArguments
    if ($LASTEXITCODE -ne 0) { throw "docker $($DockerArguments[0]) fehlgeschlagen (Exit $LASTEXITCODE)" }
}

function Get-UserEnvironmentValue([string] $Name) {
    $value = [Environment]::GetEnvironmentVariable($Name, 'User')
    if ([string]::IsNullOrEmpty($value)) {
        $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    return $value
}

function Get-MissingSecretNames {
    $missing = @()
    foreach ($name in $REQUIRED_SECRET_NAMES) {
        if ([string]::IsNullOrEmpty((Get-UserEnvironmentValue $name))) { $missing += $name }
    }
    return $missing
}

function Assert-SecretsPresent {
    $missing = Get-MissingSecretNames
    if ($missing.Count -eq 0) { return }
    $message = "Fehlende Umgebungsvariablen (nur Namen): " + ($missing -join ', ')
    if ($DryRun) { Write-Warning $message; return }
    throw $message
}

function New-EnvFileContent {
    $lines = foreach ($name in $REQUIRED_SECRET_NAMES) {
        $value = Get-UserEnvironmentValue $name
        if ($null -eq $value) { $value = '' }
        if ($value -match "[`r`n]") { throw "Secret $name enthaelt einen Zeilenumbruch." }
        "$name=$value"
    }
    return ($lines -join "`n") + "`n"
}

function Write-TempEnvFile([string] $Content) {
    $path = Join-Path $env:TEMP ("easee-ha-ocpp-" + [guid]::NewGuid().ToString('N') + '.env')
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($path, $Content, $utf8WithoutBom)
    return $path
}

function Remove-TempEnvFile([string] $Path) {
    if ($Path -and (Test-Path -LiteralPath $Path)) { Remove-Item -LiteralPath $Path -Force }
}

function Test-LegacyContainerRunning {
    $runningLegacy = Invoke-Remote "docker ps --filter 'name=^$LEGACY_CONTAINER_NAME`$' --format '{{.Names}}'"
    return -not [string]::IsNullOrWhiteSpace([string]$runningLegacy)
}

function Assert-ActuationAllowed([bool] $LegacyRunning) {
    if ($LegacyRunning -and $Actuate -and -not $StopLegacy) {
        throw "Der alte Container '$LEGACY_CONTAINER_NAME' laeuft noch. Mit -Actuate nur zusammen mit -StopLegacy starten, zwei Regler duerfen nicht aktiv regeln."
    }
}

function Confirm-UnregulatedStop {
    Write-Warning "-StopLegacy ohne -Actuate: '$LEGACY_CONTAINER_NAME' wird gestoppt, der neue Service sendet im Schattenmodus nichts. Danach regelt niemand."
    if ($Force) { return }
    if ($DryRun) {
        Write-DryRunCommand 'Bestaetigungsabfrage (im DryRun uebersprungen)'
        return
    }
    $answer = Read-Host 'Trotzdem fortfahren? (j/N)'
    if ($CONFIRMATION_ANSWERS -notcontains $answer.Trim().ToLowerInvariant()) {
        throw 'Abgebrochen, es wurde nichts geaendert.'
    }
}

function Assert-DeploymentPolicy {
    Assert-ActuationAllowed (Test-LegacyContainerRunning)
    if ($StopLegacy -and -not $Actuate) { Confirm-UnregulatedStop }
}

function Stop-LegacyContainerIfRequested {
    if (-not $StopLegacy) { return }
    if (-not (Test-LegacyContainerRunning)) { return }
    Invoke-Remote "docker rm -f $LEGACY_CONTAINER_NAME"
}

function Resolve-KeystoreSourcePath {
    if ([System.IO.Path]::IsPathRooted($KeystorePath)) { return $KeystorePath }
    return Join-Path $PROJECT_ROOT $KeystorePath
}

function Assert-KeystoreFilePresent {
    if ($DryRun) {
        Write-DryRunCommand 'Pruefung der Keystore-Datei (im DryRun nicht ausgefuehrt)'
        return
    }
    $path = Resolve-KeystoreSourcePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Keystore-Datei fehlt: $path. Erst deploy/generate-certs.ps1 ausfuehren oder -KeystorePath setzen."
    }
}

function Send-KeystoreFile {
    $localPath = Resolve-KeystoreSourcePath
    $remoteFile = "$REMOTE_SECRETS_SCP_DIR/$KEYSTORE_FILE_NAME"
    Invoke-Remote "mkdir -p $REMOTE_SECRETS_DIR && chown ${CONTAINER_UID}:${CONTAINER_UID} $REMOTE_SECRETS_DIR && chmod $SECRETS_DIR_MODE $REMOTE_SECRETS_DIR"
    if ($DryRun) {
        Write-DryRunCommand "scp <lokaler Keystore> $(Get-SshTarget):$remoteFile"
    }
    else {
        & scp $localPath "$(Get-SshTarget):$remoteFile"
        if ($LASTEXITCODE -ne 0) { throw "scp des Keystores fehlgeschlagen (Exit $LASTEXITCODE)" }
    }
    Invoke-Remote "chown ${CONTAINER_UID}:${CONTAINER_UID} $REMOTE_SECRETS_DIR/$KEYSTORE_FILE_NAME && chmod $KEYSTORE_FILE_MODE $REMOTE_SECRETS_DIR/$KEYSTORE_FILE_NAME"
}

function Publish-Image {
    if ($Local) { Publish-LocalImage; return }
    Invoke-Remote "docker pull $Image"
}

function Get-GitOutput([string[]] $GitArguments) {
    return ((& git -C $PROJECT_ROOT @GitArguments) | Out-String).Trim()
}

function Get-LocalVersion {
    $sha = Get-GitOutput @('rev-parse', "--short=$SHORT_SHA_LENGTH", 'HEAD')
    if (-not $sha) { throw 'Git-Version nicht ermittelbar (kein Git-Repository?). -ExpectedVersion angeben.' }
    if ($Local -and (Get-GitOutput @('status', '--porcelain'))) { return "$sha$DIRTY_SUFFIX" }
    return $sha
}

function Resolve-ExpectedVersion {
    if ($ExpectedVersion) { return $ExpectedVersion }
    return Get-LocalVersion
}

function Publish-LocalImage {
    Invoke-LocalDocker @('build', '--build-arg', "GIT_SHA=$script:VersionToDeploy", '-t', $Image, $PROJECT_ROOT)
    $target = Get-SshTarget
    if ($DryRun) {
        Write-DryRunCommand "docker save $Image | ssh $target docker load"
        return
    }
    & cmd.exe /c "docker save $Image | ssh $target docker load"
    if ($LASTEXITCODE -ne 0) { throw "docker save | docker load fehlgeschlagen (Exit $LASTEXITCODE)" }
}

function Send-EnvFile([string] $LocalPath) {
    $remotePath = "$REMOTE_ENV_FILE_NAME"
    Invoke-Remote "touch ~/$REMOTE_ENV_FILE_NAME && chmod 600 ~/$REMOTE_ENV_FILE_NAME"
    if ($DryRun) {
        Write-DryRunCommand "scp <temporaere env-Datei> $(Get-SshTarget):$remotePath"
        return
    }
    & scp $LocalPath "$(Get-SshTarget):$remotePath"
    if ($LASTEXITCODE -ne 0) { throw "scp fehlgeschlagen (Exit $LASTEXITCODE)" }
    Invoke-Remote "chmod 600 ~/$REMOTE_ENV_FILE_NAME"
}

function Get-PlainOcppArgument {
    if ($PlainOcpp) { return '-e EASEE_OCPP_SSL_ENABLED=false ' }
    return ''
}

function Get-RunCommand {
    $actuation = Get-ActuationValue
    return "docker run --restart always -d --name $CONTAINER_NAME " +
        "-p ${HTTP_PORT}:8080 -p ${OCPP_TLS_PORT}:8443 " +
        "-v ${DATA_VOLUME}:/data -v ${REMOTE_SECRETS_DIR}:/secrets:ro " +
        "--env-file ~/$REMOTE_ENV_FILE_NAME " +
        "-e ACTUATION_ENABLED=$actuation " +
        (Get-PlainOcppArgument) +
        $Image
}

function Replace-Container {
    Invoke-Remote "docker container rm -f $CONTAINER_NAME || true"
    Invoke-Remote (Get-RunCommand)
}

function Deploy-WithEnvFile {
    Assert-SecretsPresent
    if ($DryRun) {
        Write-DryRunCommand "temporaere env-Datei mit $($REQUIRED_SECRET_NAMES.Count) Variablen (Werte werden nicht ausgegeben)"
        Send-EnvFile '<temp.env>'
        return
    }
    $tempPath = Write-TempEnvFile (New-EnvFileContent)
    try {
        Send-EnvFile $tempPath
    }
    finally {
        Remove-TempEnvFile $tempPath
    }
}

function Show-Logs {
    & "$PSScriptRoot\logs.ps1" -DockerHost $DockerHost -SshUser $SshUser -DryRun:$DryRun
}

function Get-ServiceJson([string] $Path) {
    $url = "http://${DockerHost}:${HTTP_PORT}$Path"
    $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec $VERSION_CHECK_REQUEST_TIMEOUT_SECONDS
    # Actuator liefert einen herstellerspezifischen Content-Type; PowerShell 5.1 gibt den Inhalt dann als Bytes zurueck.
    $text = if ($response.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($response.Content) } else { [string]$response.Content }
    return @{ Status = [int]$response.StatusCode; Body = ($text | ConvertFrom-Json) }
}

function Test-ServiceMatchesVersion([string] $Version) {
    $versionResponse = Get-ServiceJson '/api/version'
    if ($versionResponse.Status -ne 200) { return "HTTP $($versionResponse.Status) bei /api/version" }
    if ($versionResponse.Body.version -ne $Version) { return "Version '$($versionResponse.Body.version)' statt '$Version'" }
    $healthResponse = Get-ServiceJson '/actuator/health'
    if ($healthResponse.Body.status -ne 'UP') { return "Health '$($healthResponse.Body.status)'" }
    return ''
}

function Assert-ServiceRunsVersion([string] $Version) {
    if ($DryRun) {
        Write-DryRunCommand "Post-Deployment-Check: GET http://${DockerHost}:${HTTP_PORT}/api/version == $Version und /actuator/health == UP (max. $VERSION_CHECK_TIMEOUT_SECONDS s)"
        return
    }
    $deadline = (Get-Date).AddSeconds($VERSION_CHECK_TIMEOUT_SECONDS)
    $lastProblem = 'noch keine Antwort'
    while ((Get-Date) -lt $deadline) {
        try { $lastProblem = Test-ServiceMatchesVersion $Version } catch { $lastProblem = "nicht erreichbar: $($_.Exception.Message)" }
        if (-not $lastProblem) {
            Write-Host "Post-Deployment-Check OK: Version $Version laeuft und ist healthy."
            return
        }
        Start-Sleep -Seconds $VERSION_CHECK_INTERVAL_SECONDS
    }
    throw "Post-Deployment-Check fehlgeschlagen nach $VERSION_CHECK_TIMEOUT_SECONDS s: $lastProblem"
}

function Invoke-Deployment {
    $script:VersionToDeploy = Resolve-ExpectedVersion
    if ($CheckOnly) {
        Assert-ServiceRunsVersion $script:VersionToDeploy
        return
    }
    Assert-DeploymentPolicy
    if (-not $PlainOcpp) { Assert-KeystoreFilePresent }
    Stop-LegacyContainerIfRequested
    Publish-Image
    Deploy-WithEnvFile
    if (-not $PlainOcpp) { Send-KeystoreFile }
    Replace-Container
    Invoke-Remote "docker ps --filter 'name=^$CONTAINER_NAME`$' --format '{{.Status}}'"
    Assert-ServiceRunsVersion $script:VersionToDeploy
    Show-Logs
}

Invoke-Deployment
