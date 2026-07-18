[CmdletBinding()]
param(
    [switch]$Sonar,
    [switch]$SkipPlaywright,
    [switch]$SkipPlaywrightBrowserInstall,
    [string]$SonarHostUrl = 'https://sonarqube.hictsapps.nl',
    [string]$SonarProjectKey = 'mrhoeve_mph',
    [string]$SonarProjectName = 'Maven Project Helper',
    [string]$SonarTokenFile = '.sonar-token'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = $PSScriptRoot
$frontendRoot = Join-Path $repositoryRoot 'frontend\mph'
$frontendBin = Join-Path $frontendRoot 'node_modules\.bin'
$isWindows = [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
    [System.Runtime.InteropServices.OSPlatform]::Windows
)

function Resolve-NpmCommand {
    if (-not [string]::IsNullOrWhiteSpace($env:NPM_CMD)) {
        if (-not (Test-Path -LiteralPath $env:NPM_CMD -PathType Leaf)) {
            throw "NPM_CMD points to a file that does not exist: $($env:NPM_CMD)"
        }
        return (Resolve-Path -LiteralPath $env:NPM_CMD).Path
    }

    foreach ($name in @('npm.cmd', 'npm')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            return $command.Source
        }
    }

    $nvmForWindowsNpm = 'C:\nvm4w\nodejs\npm.cmd'
    if (Test-Path -LiteralPath $nvmForWindowsNpm -PathType Leaf) {
        return $nvmForWindowsNpm
    }

    throw 'npm was not found. Add it to PATH or set NPM_CMD to the full npm executable path.'
}

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory)] [string]$Description,
        [Parameter(Mandatory)] [string]$Command,
        [Parameter(Mandatory)] [string[]]$Arguments,
        [Parameter(Mandatory)] [string]$WorkingDirectory
    )

    Write-Host "`n==> $Description" -ForegroundColor Cyan
    Push-Location $WorkingDirectory
    try {
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Description failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
}

function Read-SonarToken {
    if (-not [string]::IsNullOrWhiteSpace($env:SONAR_TOKEN)) {
        return $env:SONAR_TOKEN
    }

    $tokenPath = if ([System.IO.Path]::IsPathRooted($SonarTokenFile)) {
        $SonarTokenFile
    } else {
        Join-Path $repositoryRoot $SonarTokenFile
    }

    if (Test-Path -LiteralPath $tokenPath -PathType Leaf) {
        $token = (Get-Content -LiteralPath $tokenPath -Raw).Trim()
        if (-not [string]::IsNullOrWhiteSpace($token)) {
            return $token
        }
        throw "The Sonar token file is empty: $tokenPath"
    }

    throw "No Sonar token was found. Set SONAR_TOKEN or create the git-ignored file '$tokenPath'."
}

function Normalize-FrontendCoverage {
    $lcovPath = Join-Path $frontendRoot 'coverage\mph\lcov.info'
    if (-not (Test-Path -LiteralPath $lcovPath -PathType Leaf)) {
        throw "Frontend coverage report was not generated: $lcovPath"
    }

    $normalizedLines = Get-Content -LiteralPath $lcovPath | ForEach-Object {
        if ($_.StartsWith('SF:')) {
            $path = $_.Substring(3).Replace('\', '/')
            if ($path.StartsWith('src/')) {
                $path = "frontend/mph/$path"
            }
            "SF:$path"
        } else {
            $_
        }
    }
    [System.IO.File]::WriteAllLines(
        $lcovPath,
        [string[]]$normalizedLines,
        [System.Text.UTF8Encoding]::new($false)
    )
}

$npmCommand = Resolve-NpmCommand
$mavenCommand = Join-Path $repositoryRoot $(if ($isWindows) { 'mvnw.cmd' } else { 'mvnw' })
$originalPath = $env:PATH
$originalSonarToken = [Environment]::GetEnvironmentVariable('SONAR_TOKEN', 'Process')
$sonarToken = $null

try {
    if ($Sonar) {
        $sonarToken = Read-SonarToken
    }

    Invoke-CheckedCommand `
        -Description 'Installing locked frontend dependencies' `
        -Command $npmCommand `
        -Arguments @('ci') `
        -WorkingDirectory $frontendRoot

    if (-not $SkipPlaywright -and -not $SkipPlaywrightBrowserInstall) {
        Invoke-CheckedCommand `
            -Description 'Installing the Playwright Chromium runtime' `
            -Command $npmCommand `
            -Arguments @('exec', '--', 'playwright', 'install', 'chromium') `
            -WorkingDirectory $frontendRoot
    }

    Invoke-CheckedCommand `
        -Description 'Running frontend unit tests with coverage' `
        -Command $npmCommand `
        -Arguments @('run', 'test:coverage') `
        -WorkingDirectory $frontendRoot
    Normalize-FrontendCoverage

    $env:PATH = "$frontendBin$([System.IO.Path]::PathSeparator)$originalPath"
    $mavenArguments = @('-B', 'clean', 'verify')

    if ($Sonar) {
        $env:SONAR_TOKEN = $sonarToken
        $mavenArguments += @(
            'org.sonarsource.scanner.maven:sonar-maven-plugin:5.7.0.6970:sonar',
            "-Dsonar.projectKey=$SonarProjectKey",
            "-Dsonar.projectName=$SonarProjectName",
            "-Dsonar.host.url=$SonarHostUrl"
        )
    }

    Invoke-CheckedCommand `
        -Description $(if ($Sonar) { 'Running the Maven verification build and SonarQube analysis' } else { 'Running the Maven verification build' }) `
        -Command $mavenCommand `
        -Arguments $mavenArguments `
        -WorkingDirectory $repositoryRoot

    if (-not $SkipPlaywright) {
        Invoke-CheckedCommand `
            -Description 'Running Playwright full-stack tests' `
            -Command $npmCommand `
            -Arguments @('run', 'test:e2e') `
            -WorkingDirectory $frontendRoot
    }

    Write-Host "`nLocal build completed successfully." -ForegroundColor Green
} finally {
    $env:PATH = $originalPath
    [Environment]::SetEnvironmentVariable('SONAR_TOKEN', $originalSonarToken, 'Process')
}
