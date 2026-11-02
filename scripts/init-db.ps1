# Create database okex_alpha (idempotent) and apply db/schema.sql (idempotent).
# Usage: powershell -File scripts/init-db.ps1 [-Database okex_alpha] [-Psql <path to psql.exe>]
# NOTE: this file must stay ASCII-only: Windows PowerShell 5.1 reads .ps1 as ANSI and
#       multi-byte UTF-8 comment bytes can swallow the CRLF and break parsing.
param(
    [string]$PgHost = "localhost",
    [int]$PgPort = 5432,
    [string]$PgUser = "postgres",
    [string]$PgPassword = $env:OKX_DB_PASSWORD,
    [string]$Database = "okex_alpha",
    [string]$Psql = "D:\Program Files\PostgreSQL\18\bin\psql.exe"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrEmpty($PgPassword)) {
    throw "PG password not provided. Set the OKX_DB_PASSWORD environment variable or pass -PgPassword <value>."
}

if (-not (Test-Path $Psql)) {
    $cmd = Get-Command psql -ErrorAction SilentlyContinue
    if (-not $cmd) { throw "psql not found; pass -Psql <path>" }
    $Psql = $cmd.Source
}

$env:PGPASSWORD = $PgPassword
$base = @("-h", $PgHost, "-p", "$PgPort", "-U", $PgUser, "-v", "ON_ERROR_STOP=1")

Write-Host "==> creating database $Database (if absent)"
& $Psql @base -d postgres -f (Join-Path $Root "db\01-create-database.sql")
if ($LASTEXITCODE -ne 0) { throw "create database failed" }

Write-Host "==> applying schema"
& $Psql @base -d $Database -f (Join-Path $Root "db\schema.sql")
if ($LASTEXITCODE -ne 0) { throw "schema apply failed" }

Write-Host "==> tables"
& $Psql @base -d $Database -c "\dt okx_*"
