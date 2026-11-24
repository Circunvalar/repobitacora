# Bitácora Digital: arranca el programa sin instalar nada.
# 1) Usa el Java portátil de la carpeta "jre"; si no existe, usa Java 21+ del equipo;
#    si tampoco hay, descarga una vez el Java portátil (Eclipse Temurin 21, ~45 MB) a la carpeta "jre".
# 2) Guarda la base de datos en %LOCALAPPDATA%\BitacoraDigital (fuera de OneDrive, para que la sincronización no la dañe).
# 3) Abre el navegador cuando el programa está listo.
$ErrorActionPreference = 'Stop'
$base = $PSScriptRoot
Set-Location $base
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# Puerto configurado (por defecto 4444)
$puerto = 4444
$cfg = Join-Path $base 'config\application.properties'
if (Test-Path $cfg) {
    $linea = Select-String -Path $cfg -Pattern '^\s*quarkus\.http\.port\s*=\s*(\d+)' | Select-Object -First 1
    if ($linea) { $puerto = [int]$linea.Matches[0].Groups[1].Value }
}
$url = "http://localhost:$puerto"

# ¿Ya está corriendo?
try {
    Invoke-WebRequest "$url/q/health/ready" -UseBasicParsing -TimeoutSec 2 | Out-Null
    Write-Host "La Bitácora ya está abierta en $url"
    Start-Process $url
    exit 0
} catch { }

function Version-Java([string]$exe) {
    $ErrorActionPreference = 'Continue'
    try {
        $salida = & $exe -version 2>&1 | Out-String
        if ($salida -match 'version "(\d+)') { return [int]$Matches[1] }
    } catch { }
    return 0
}

$java = Join-Path $base 'jre\bin\java.exe'
if (-not (Test-Path $java)) {
    $sistema = Get-Command java -ErrorAction SilentlyContinue
    if ($sistema -and (Version-Java $sistema.Source) -ge 21) {
        $java = $sistema.Source
    } else {
        Write-Host 'No se encontró Java 21. Descargando Java portátil (solo la primera vez, ~45 MB)...'
        $zip = Join-Path $env:TEMP 'bitacora-jre.zip'
        $tmp = Join-Path $base 'jre-descarga'
        $origen = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse'
        Invoke-WebRequest $origen -OutFile $zip -UseBasicParsing
        if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
        Expand-Archive $zip -DestinationPath $tmp -Force
        $interna = Get-ChildItem $tmp -Directory | Select-Object -First 1
        Move-Item $interna.FullName (Join-Path $base 'jre')
        Remove-Item $tmp -Recurse -Force
        Remove-Item $zip -Force
        $java = Join-Path $base 'jre\bin\java.exe'
        Write-Host 'Java portátil listo.'
    }
}

$datosLocales = Join-Path $env:LOCALAPPDATA 'BitacoraDigital'
New-Item -ItemType Directory -Force -Path (Join-Path $datosLocales 'db') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $base 'datos\entrada') | Out-Null
$env:BITACORA_DB = Join-Path $datosLocales 'db\bitacora'

# Abre el navegador cuando el programa responde
Start-Job -ArgumentList $url -ScriptBlock {
    param($u)
    for ($i = 0; $i -lt 120; $i++) {
        try { Invoke-WebRequest "$u/q/health/ready" -UseBasicParsing -TimeoutSec 2 | Out-Null; Start-Process $u; break }
        catch { Start-Sleep -Seconds 1 }
    }
} | Out-Null

$ErrorActionPreference = 'Continue'
Write-Host ''
Write-Host "Bitácora Digital iniciando en $url"
Write-Host "Base de datos: $($env:BITACORA_DB)"
Write-Host 'Para detener el programa, cierre esta ventana.'
Write-Host ''
& $java -Xmx768m -Dfile.encoding=UTF-8 -jar (Join-Path $base 'bitacora-digital.jar')
exit $LASTEXITCODE
