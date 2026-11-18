#!/usr/bin/env sh
set -eu
PACKAGE=com.dexterh4ck.equipos
COMPONENT=com.dexterh4ck.equipos/.service.KioskDeviceAdminReceiver
command -v adb >/dev/null 2>&1 || { echo "ERROR: instala Android Platform Tools"; exit 1; }
adb devices
adb shell pm path "$PACKAGE" >/dev/null 2>&1 || {
  echo "ERROR: instala dexterh4ck-equipos.apk primero: adb install -r dexterh4ck-equipos.apk"
  exit 1
}
printf "Device Owner concede administracion completa. Escribe ACTIVAR para continuar: "
read answer
[ "$answer" = "ACTIVAR" ] || exit 1
adb shell dpm set-device-owner "$COMPONENT"
adb shell dumpsys device_policy
