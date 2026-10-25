#Requires -Version 5.1
<#
.SYNOPSIS
    Deployt easee-ha-ocpp auf den Docker-Host (Standard: docker.fritz.box) per SSH.

.DESCRIPTION
    Holt das Image aus GHCR oder baut es lokal (-Local) und uebertraegt es per
    `docker save | docker load`. Secrets kommen aus den lokalen User-Umgebungsvariablen,
    werden in eine temporaere env-Datei geschrieben, per scp nach ~/easee-ha-ocpp.env
    (Rechte 600) uebertragen und nur ueber `docker run --env-file` uebergeben.
    Ohne -StopLegacy bricht das Skript ab, solange der alte Regler pv-surcharge laeuft
    (zwei Regler duerfen nie parallel laufen).

.PARAMETER DryRun
    Zeigt nur die Kommandos an, fuehrt nichts aus und liest keine Secrets.

.PARAMETER Actuate
    Setzt ACTUATION_ENABLED=true. Standard ist der Schattenmodus (false).
#>
[CmdletBinding()]
param(
    [string] $DockerHost = 'docker.fritz.box',
    [string] $SshUser = 'root',
    [string] $Image = 'ghcr.io/ppuetsch/easee-ha-ocpp:latest',
    [switch] $Local,
    [switch] $StopLegacy,
    [switch] $DryRun,
    [switch] $Actuate
)

$ErrorActionPreference = 'Stop'

$CONTAINER_NAME = 'easee-ha-ocpp'
$LEGACY_CONTAINER_NAME = 'pv-surcharge'
$REMOTE_ENV_FILE_NAME = 'easee-ha-ocpp.env'
$REMOTE_SECRETS_DIR = '~/easee-secrets'
$HTTP_PORT = 8080
$OCPP_TLS_PORT = 8443
$DATA_VOLUME = 'easee-data'
$REQUIRED_SECRET_NAMES = @('HA_TOKEN', 'EASEE_USERNAME', 'EASEE_PASSWORD', 'OCPP_BASIC_AUTH_PASSWORD', 'KEYSTORE_PASSWORD')
$PROJECT_ROOT = Split-Path -Parent $PSScriptRoot

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

function Assert-LegacyContainerStopped {
    $runningLegacy = Invoke-Remote "docker ps --filter 'name=^$LEGACY_CONTAINER_NAME`$' --format '{{.Names}}'"
    if ([string]::IsNullOrWhiteSpace([string]$runningLegacy)) { return }
    if (-not $StopLegacy) {
        throw "Der alte Container '$LEGACY_CONTAINER_NAME' laeuft noch. Mit -StopLegacy stoppen und entfernen."
    }
    Invoke-Remote "docker rm -f $LEGACY_CONTAINER_NAME"
}

function Publish-Image {
    if ($Local) { Publish-LocalImage; return }
    Invoke-Remote "docker pull $Image"
}

function Publish-LocalImage {
    Invoke-LocalDocker @('build', '-t', $Image, $PROJECT_ROOT)
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

function Get-RunCommand {
    $actuation = Get-ActuationValue
    return "docker run --restart always -d --name $CONTAINER_NAME " +
        "-p ${HTTP_PORT}:8080 -p ${OCPP_TLS_PORT}:8443 " +
        "-v ${DATA_VOLUME}:/data -v ${REMOTE_SECRETS_DIR}:/secrets:ro " +
        "--env-file ~/$REMOTE_ENV_FILE_NAME " +
        "-e ACTUATION_ENABLED=$actuation " +
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

function Invoke-Deployment {
    Assert-LegacyContainerStopped
    Publish-Image
    Deploy-WithEnvFile
    Replace-Container
    Invoke-Remote "docker ps --filter 'name=^$CONTAINER_NAME`$' --format '{{.Status}}'"
    Show-Logs
}

Invoke-Deployment
