# Run Maven (auto-downloads Apache Maven 3.9.16 into .tools/ when missing).
# Usage: powershell -File scripts/mvn.ps1 clean package
# NOTE: this file must stay ASCII-only: Windows PowerShell 5.1 reads .ps1 as ANSI and
#       multi-byte UTF-8 comment bytes can swallow the CRLF and break parsing.
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Goals)

$ErrorActionPreference = "Stop"
$MavenVersion = "3.9.16"
$Root = Split-Path -Parent $PSScriptRoot
$Tools = Join-Path $Root ".tools"
$MavenHome = Join-Path $Tools "apache-maven-$MavenVersion"
$MvnCmd = Join-Path $MavenHome "bin\mvn.cmd"

if (-not (Test-Path $MvnCmd)) {
    New-Item -ItemType Directory -Force -Path $Tools | Out-Null
    $Zip = Join-Path $Tools "apache-maven-$MavenVersion-bin.zip"
    if (-not (Test-Path $Zip)) {
        $name = "apache-maven-$MavenVersion-bin.zip"
        $urls = @(
            "https://dlcdn.apache.org/maven/maven-3/$MavenVersion/binaries/$name",
            "https://archive.apache.org/dist/maven/maven-3/$MavenVersion/binaries/$name"
        )
        $ok = $false
        foreach ($u in $urls) {
            try {
                Write-Host "Downloading $u"
                Invoke-WebRequest -Uri $u -OutFile $Zip -UseBasicParsing
                $ok = $true
                break
            } catch {
                Write-Warning "failed: $u ($($_.Exception.Message))"
            }
        }
        if (-not $ok) { throw "cannot download Apache Maven $MavenVersion" }
    }
    Expand-Archive -Path $Zip -DestinationPath $Tools -Force
}

if (-not $env:JAVA_HOME) {
    $candidate = "D:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot"
    if (Test-Path (Join-Path $candidate "bin\java.exe")) {
        $env:JAVA_HOME = $candidate
    } else {
        throw "JAVA_HOME is not set and the default JDK path is missing"
    }
}

if (-not $Goals -or $Goals.Count -eq 0) { $Goals = @("clean", "package") }

Push-Location (Join-Path $Root "backend")
try {
    & $MvnCmd -B -ntp @Goals
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
