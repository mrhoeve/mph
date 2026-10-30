[CmdletBinding()]
param(
    [string]$JavaHome,
    [switch]$SkipClean,
    [switch]$SkipVerification,
    [switch]$Sonar,
    [string]$SonarHostUrl = 'https://sonarqube.hictsapps.nl',
    [string]$SonarProjectKey = 'mrhoeve_mph',
    [string]$SonarProjectName = 'Maven Project Helper',
    [string]$SonarOrganization,
    [string]$SonarTokenFile = '.sonar-token'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

& (Join-Path $PSScriptRoot 'build-plugin-local.ps1') @PSBoundParameters
