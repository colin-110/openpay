<#
.SYNOPSIS
    Starts the whole OpenPay stack locally, one service per window.

.DESCRIPTION
    The platform is seven services plus infrastructure. Starting them by hand means seven
    terminals in the right order with the right environment in each, which is tedious enough
    that it stops people from running the thing at all.

    Infrastructure must already be up:
        docker compose -f platform/docker/docker-compose.yml up -d

.PARAMETER Stop
    Stop everything listening on the OpenPay ports instead of starting.

.EXAMPLE
    .\scripts\run-local.ps1
    .\scripts\run-local.ps1 -Stop
#>
param(
    [switch]$Stop,
    [string]$AdminToken = "dev-admin-token",
    [string]$BankASecret = "bank-a-secret",
    [string]$BankBSecret = "bank-b-secret"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$ports = @(8080, 8081, 8082, 8083, 8084, 8085, 8086, 8087, 8088, 9001, 9002)

if ($Stop) {
    foreach ($port in $ports) {
        $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
        if ($conn) {
            Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
            Write-Host "stopped port $port"
        }
    }
    Write-Host "`nInfrastructure is still running. To stop it too:"
    Write-Host "  docker compose -f platform/docker/docker-compose.yml down"
    return
}

# Fail early with a clear message rather than letting seven services each fail to connect.
$pg = docker ps --filter "name=openpay-postgres" --filter "status=running" --format "{{.Names}}"
$kafka = docker ps --filter "name=openpay-kafka" --filter "status=running" --format "{{.Names}}"
if (-not $pg -or -not $kafka) {
    Write-Host "PostgreSQL and Kafka must be running first:" -ForegroundColor Yellow
    Write-Host "  docker compose -f platform/docker/docker-compose.yml up -d postgres kafka"
    return
}

foreach ($port in $ports) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        Write-Host "Port $port is already in use. Run with -Stop first." -ForegroundColor Yellow
        return
    }
}

# name, maven module, extra environment
$services = @(
    @{ Name = "merchant"; Module = "services/merchant-service"; Env = @{} },
    @{ Name = "auth";     Module = "services/auth-service";     Env = @{} },
    @{ Name = "payment";  Module = "services/payment-service";  Env = @{} },
    @{ Name = "webhook";  Module = "services/webhook-service";
       Env = @{ MOCK_BANK_A_SECRET = $BankASecret; MOCK_BANK_B_SECRET = $BankBSecret } },
    @{ Name = "router";   Module = "services/provider-router-service"; Env = @{} },
    @{ Name = "ledger";   Module = "services/ledger-service";   Env = @{} },
    @{ Name = "settlement"; Module = "services/settlement-service"; Env = @{} },
    @{ Name = "notification"; Module = "services/notification-service"; Env = @{} },
    @{ Name = "gateway";  Module = "services/gateway-service";  Env = @{} },
    @{ Name = "bank-a";   Module = "services/mock-bank-service";
       Env = @{ BANK_NAME = "mock-bank-a"; BANK_PORT = "9001"; BANK_SIGNING_SECRET = $BankASecret } },
    @{ Name = "bank-b";   Module = "services/mock-bank-service";
       Env = @{ BANK_NAME = "mock-bank-b"; BANK_PORT = "9002"; BANK_SIGNING_SECRET = $BankBSecret } }
)

Write-Host "Starting $($services.Count) services..." -ForegroundColor Cyan

foreach ($service in $services) {
    $envSetup = "`$env:OPENPAY_ADMIN_TOKEN='$AdminToken'; "
    foreach ($key in $service.Env.Keys) {
        $envSetup += "`$env:$key='$($service.Env[$key])'; "
    }
    $title = "openpay-$($service.Name)"
    $command = "$envSetup `$host.UI.RawUI.WindowTitle='$title'; " +
               "Set-Location '$root'; .\mvnw.cmd -B -pl $($service.Module) spring-boot:run"

    Start-Process powershell -ArgumentList "-NoExit", "-Command", $command | Out-Null
    Write-Host "  launched $title"
}

Write-Host "`nWaiting for all services to report UP (this takes a minute or two)..." -ForegroundColor Cyan

$deadline = (Get-Date).AddMinutes(5)
while ((Get-Date) -lt $deadline) {
    $up = 0
    foreach ($port in $ports) {
        try {
            $response = Invoke-RestMethod -Uri "http://localhost:$port/actuator/health" -TimeoutSec 2
            if ($response.status -eq "UP") { $up++ }
        } catch { }
    }
    Write-Host "  $up/$($ports.Count) up"
    if ($up -eq $ports.Count) {
        Write-Host "`nAll services are up." -ForegroundColor Green
        Write-Host "`nRun the acceptance suite:"
        Write-Host "  `$env:OPENPAY_ADMIN_TOKEN='$AdminToken'; bash scripts/e2e.sh"
        return
    }
    Start-Sleep -Seconds 5
}

Write-Host "`nTimed out waiting. Check the service windows for errors." -ForegroundColor Yellow
