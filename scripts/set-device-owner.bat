@echo off
setlocal
set PACKAGE=com.dexterh4ck.equipos
set COMPONENT=com.dexterh4ck.equipos/.service.KioskDeviceAdminReceiver

where adb >nul 2>nul
if errorlevel 1 (
  echo ERROR: adb.exe no esta en PATH. Instala Android Platform Tools.
  exit /b 1
)

echo Dispositivos conectados:
adb devices
echo.
echo Verificando que Equipos esta instalado...
adb shell pm path %PACKAGE% >nul 2>nul
if errorlevel 1 (
  echo ERROR: Dexterh4ck Equipos no esta instalado.
  echo Ejecuta: adb install -r dexterh4ck-equipos.apk
  exit /b 1
)

echo.
echo ATENCION: Device Owner otorga administracion completa del dispositivo.
echo Continua solo si eres propietario o tienes autorizacion.
set /p CONFIRM=Escribe ACTIVAR para continuar: 
if /I not "%CONFIRM%"=="ACTIVAR" exit /b 1

adb shell dpm set-device-owner %COMPONENT%
if errorlevel 1 (
  echo.
  echo No se pudo activar Device Owner. Consulta docs/DEVICE_OWNER_SETUP.md.
  exit /b 1
)

echo.
echo Estado de politicas del dispositivo:
adb shell dumpsys device_policy
endlocal
