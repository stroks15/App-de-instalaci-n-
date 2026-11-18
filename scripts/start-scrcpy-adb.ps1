param(
    [string]$Serial = "",
    [int]$MaxSize = 1280,
    [string]$BitRate = "4M"
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "No se encontró adb. Instala Android Platform Tools y agrega platform-tools al PATH."
}
if (-not (Get-Command scrcpy -ErrorAction SilentlyContinue)) {
    throw "No se encontró scrcpy. Instala scrcpy oficial y agrega su carpeta al PATH."
}

$devices = @(adb devices | Select-String "^S+s+device$")
if ($devices.Count -eq 0) {
    throw "No hay equipos ADB autorizados. Activa Depuración USB/inalámbrica y acepta la huella RSA."
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    if ($devices.Count -eq 1) {
        $Serial = ($devices[0].Line -split "s+")[0]
    } else {
        Write-Host "Equipos ADB disponibles:"
        for ($i = 0; $i -lt $devices.Count; $i++) {
            Write-Host "[$i] $($devices[$i].Line)"
        }
        $selection = [int](Read-Host "Selecciona el índice")
        if ($selection -lt 0 -or $selection -ge $devices.Count) {
            throw "Selección fuera de rango."
        }
        $Serial = ($devices[$selection].Line -split "s+")[0]
    }
}

$authorized = adb -s $Serial get-state
if ($LASTEXITCODE -ne 0 -or $authorized -ne "device") {
    throw "El equipo $Serial no está conectado/autorizado."
}

Write-Host "Abriendo pantalla remota con interacción para $Serial. Cierra scrcpy para finalizar."
scrcpy --serial $Serial --max-size $MaxSize --video-bit-rate $BitRate --stay-awake
if ($LASTEXITCODE -ne 0) {
    throw "scrcpy terminó con error ($LASTEXITCODE). Revisa compatibilidad ADB/Fire OS y versión scrcpy."
}
