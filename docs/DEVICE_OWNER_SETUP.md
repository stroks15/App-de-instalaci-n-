# Activar Dexterh4ck Equipos como Device Owner

> Esto administra por completo el dispositivo. Hazlo únicamente en equipos propios o con autorización del propietario. El proceso normalmente exige un dispositivo recién restaurado de fábrica y sin cuentas ya configuradas. **El comando no borra el equipo por sí solo**, pero la preparación puede requerir restablecimiento de fábrica. Haz copia de seguridad antes.

## Requisitos

- Instalar Dexterh4ck Equipos en el Android TV / TV Box / Fire TV compatible.
- Activar Opciones de desarrollador y Depuración USB/ADB.
- Conectar el dispositivo al PC y aceptar la huella RSA.
- Confirmar que `adb devices` muestra el equipo como `device`, no `unauthorized`.
- El dispositivo debe cumplir los requisitos de Android para aprovisionar un Device Owner; normalmente no debe tener cuentas de usuario ya añadidas.

## Windows (PowerShell o CMD)

Desde la carpeta donde está `adb.exe`:

```bat
adb devices
adb install -r dexterh4ck-equipos.apk
adb shell dpm set-device-owner com.dexterh4ck.equipos/.service.KioskDeviceAdminReceiver
adb shell dumpsys device_policy
```

También puedes ejecutar `scripts\\set-device-owner.bat` después de instalar el APK y conectar el equipo.

## Linux / macOS

```sh
adb devices
adb install -r dexterh4ck-equipos.apk
adb shell dpm set-device-owner com.dexterh4ck.equipos/.service.KioskDeviceAdminReceiver
adb shell dumpsys device_policy
```

## Comprobar desde la app

Abre Dexterh4ck Equipos. Si el aprovisionamiento fue correcto, la app detecta Device Owner y configura:
- Dexterh4ck Equipos como Home persistente.
- Su paquete en la lista permitida de Lock Task.
- El modo de administración dedicado.

## Qué habilita

- Instalación y desinstalación silenciosa mediante PackageInstaller, sin diálogo de usuario, mientras siga siendo Device Owner.
- Bloqueo real de apps mediante `setApplicationHidden`.
- Kiosk / Lock Task y restauración del launcher al reiniciar.

## Límites importantes

- Android no permite a una app reiniciarse después de que el usuario use **Forzar detención** desde Ajustes. El sistema bloquea sus receptores y alarmas hasta que se abra manualmente otra vez. Device Owner + Home persistente + Lock Task ayudan a impedir cierres accidentales y recuperar el agente tras reinicios, pero no anulan el botón Forzar detención.
- Desinstalar, quitar el administrador o restablecer de fábrica elimina la administración.
- Fire OS varía por versión/modelo; algunos Fire TV bloquean el aprovisionamiento DPM o las APIs empresariales. Verifica el resultado con `dumpsys device_policy`.
- Si el comando falla con `already provisioned`, `accounts`, `Not allowed to set the device owner` u otro error, no lo fuerces: revisa las condiciones del fabricante o restablece el equipo tras respaldar los datos.
- La instalación silenciosa sólo se promete cuando el agente es Device Owner y el sistema permite esa operación.
