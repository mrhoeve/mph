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

$repositoryRoot = $PSScriptRoot
$pluginRoot = Join-Path $repositoryRoot 'intellij-plugin'
$isWindows = [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
    [System.Runtime.InteropServices.OSPlatform]::Windows
)

function Test-Java21Home {
    param([string]$Candidate)

    if ([string]::IsNullOrWhiteSpace($Candidate)) {
        return $false
    }

    $releaseFile = Join-Path $Candidate 'release'
    $javaExecutable = Join-Path $Candidate $(if ($isWindows) { 'bin\java.exe' } else { 'bin/java' })
    if (-not (Test-Path -LiteralPath $releaseFile -PathType Leaf) -or
        -not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
        return $false
    }

    return [bool](Select-String -LiteralPath $releaseFile -Pattern '^JAVA_VERSION="21(?:\.|\")' -Quiet)
}

function Get-Java21Version {
    param([string]$Candidate)

    $releaseFile = Join-Path $Candidate 'release'
    $versionLine = Select-String -LiteralPath $releaseFile -Pattern '^JAVA_VERSION="([0-9.]+)' |
        Select-Object -First 1
    if ($null -ne $versionLine -and $versionLine.Matches.Count -gt 0) {
        return [version]$versionLine.Matches[0].Groups[1].Value
    }
    return [version]'0.0'
}

function Resolve-Java21Home {
    if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
        if (-not (Test-Java21Home $JavaHome)) {
            throw "-JavaHome must point to a JDK 21 installation containing bin/java and a Java 21 release file: $JavaHome"
        }
        return (Resolve-Path -LiteralPath $JavaHome).Path
    }

    foreach ($environmentName in @('MPH_JAVA_HOME_21', 'JAVA_HOME_21_X64', 'JDK_21', 'JAVA_HOME')) {
        $value = [Environment]::GetEnvironmentVariable($environmentName, 'Process')
        if (-not [string]::IsNullOrWhiteSpace($value) -and (Test-Java21Home $value)) {
            return (Resolve-Path -LiteralPath $value).Path
        }
    }

    $candidates = [System.Collections.Generic.List[string]]::new()
    $searchRoots = [System.Collections.Generic.List[string]]::new()
    foreach ($root in @('D:\devtools', 'C:\devtools')) {
        if (-not [string]::IsNullOrWhiteSpace($root)) {
            $searchRoots.Add($root)
        }
    }
    if (-not [string]::IsNullOrWhiteSpace(${env:ProgramFiles})) {
        foreach ($folder in @('Java', 'Eclipse Adoptium', 'Amazon Corretto', 'Microsoft')) {
            $searchRoots.Add((Join-Path ${env:ProgramFiles} $folder))
        }
    }

    foreach ($root in $searchRoots) {
        if (Test-Java21Home $root) {
            $candidates.Add($root)
        }
        if (Test-Path -LiteralPath $root -PathType Container) {
            Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                $candidates.Add($_.FullName)
            }
        }
    }

    $newestCandidate = $candidates |
        Select-Object -Unique |
        Where-Object { Test-Java21Home $_ } |
        Sort-Object { Get-Java21Version $_ } -Descending |
        Select-Object -First 1
    if ($null -ne $newestCandidate) {
        return (Resolve-Path -LiteralPath $newestCandidate).Path
    }

    throw @"
JDK 21 was not found. Install JDK 21, then use one of these options:
  .\build-plugin-local.cmd -JavaHome 'D:\path\to\jdk-21'
  `$env:MPH_JAVA_HOME_21 = 'D:\path\to\jdk-21'

JAVA_HOME may continue to point to a different JDK; MPH_JAVA_HOME_21 is only used by the plugin build.
"@
}

function Read-SonarToken {
    $environmentToken = [Environment]::GetEnvironmentVariable('SONAR_TOKEN', 'Process')
    if (-not [string]::IsNullOrWhiteSpace($environmentToken)) {
        return $environmentToken
    }

    $tokenPath = if ([IO.Path]::IsPathRooted($SonarTokenFile)) {
        $SonarTokenFile
    } else {
        Join-Path $repositoryRoot $SonarTokenFile
    }
    if (-not (Test-Path -LiteralPath $tokenPath -PathType Leaf)) {
        throw "No Sonar token was found. Set SONAR_TOKEN or create the git-ignored file '$tokenPath'."
    }

    $token = (Get-Content -LiteralPath $tokenPath -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($token)) {
        throw "The Sonar token file is empty: $tokenPath"
    }
    return $token
}

$resolvedJavaHome = Resolve-Java21Home
$gradleWrapper = Join-Path $pluginRoot $(if ($isWindows) { 'gradlew.bat' } else { 'gradlew' })
$gradleArguments = [System.Collections.Generic.List[string]]::new()
if (-not $SkipClean) {
    $gradleArguments.Add('clean')
}
$gradleArguments.Add('koverXmlReport')
$gradleArguments.Add('buildPlugin')
if (-not $SkipVerification) {
    $gradleArguments.Add('verifyPlugin')
}
if ($Sonar) {
    $gradleArguments.Add('sonar')
    $gradleArguments.Add("-Dsonar.host.url=$SonarHostUrl")
    $gradleArguments.Add("-Dsonar.projectKey=$SonarProjectKey")
    $gradleArguments.Add("-Dsonar.projectName=$SonarProjectName")
    $gradleArguments.Add('-Dsonar.qualitygate.wait=true')
    $gradleArguments.Add('-Dsonar.qualitygate.timeout=300')
    if (-not [string]::IsNullOrWhiteSpace($SonarOrganization)) {
        $gradleArguments.Add("-Dsonar.organization=$SonarOrganization")
    }
}
$gradleArguments.Add('--console=plain')

$originalJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
$originalPath = $env:PATH
$originalSonarToken = [Environment]::GetEnvironmentVariable('SONAR_TOKEN', 'Process')

try {
    $env:JAVA_HOME = $resolvedJavaHome
    $env:PATH = "$(Join-Path $resolvedJavaHome 'bin')$([System.IO.Path]::PathSeparator)$originalPath"
    if ($Sonar) {
        $env:SONAR_TOKEN = Read-SonarToken
    }
    Write-Host "`n==> Using JDK 21 from $resolvedJavaHome" -ForegroundColor Cyan
    Write-Host $(if ($Sonar) { '==> Testing, packaging, verifying, and analyzing the IntelliJ plugin' } else { '==> Testing and packaging the IntelliJ plugin' }) -ForegroundColor Cyan

    Push-Location $pluginRoot
    try {
        if ($isWindows) {
            & $gradleWrapper @gradleArguments
        } else {
            & bash $gradleWrapper @gradleArguments
        }
        if ($LASTEXITCODE -ne 0) {
            throw "The IntelliJ plugin build failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }

    $distribution = Get-ChildItem -LiteralPath (Join-Path $pluginRoot 'build\distributions') -Filter '*.zip' |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    Write-Host "`nPlugin build completed successfully." -ForegroundColor Green
    if ($null -ne $distribution) {
        Write-Host "Package: $($distribution.FullName)" -ForegroundColor Green
    }
} finally {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $originalJavaHome, 'Process')
    [Environment]::SetEnvironmentVariable('SONAR_TOKEN', $originalSonarToken, 'Process')
    $env:PATH = $originalPath
}
