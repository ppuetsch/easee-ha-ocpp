#Requires -Version 5.1
<#
.SYNOPSIS
    Verfolgt die Logs des easee-ha-ocpp-Containers auf dem Docker-Host.
#>
[CmdletBinding()]
param(
    [string] $DockerHost = 'docker.fritz.box',
    [string] $SshUser = 'root',
    [string] $Since = '4h',
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'

$CONTAINER_NAME = 'easee-ha-ocpp'
$target = "$SshUser@$DockerHost"
$remoteCommand = "docker logs $CONTAINER_NAME --since $Since --follow"

if ($DryRun) {
    Write-Host "[DryRun] ssh $target '$remoteCommand'"
    return
}

& ssh $target $remoteCommand
