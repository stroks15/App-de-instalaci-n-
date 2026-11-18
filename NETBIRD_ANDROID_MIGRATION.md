# KioskGrid: migración Android a NetBird + ADB TCP

## Decisiones de arquitectura

- NetBird es el plano de red privado. La app no crea un túnel WireGuard paralelo ni usa las IPs VPN antiguas.
- En Android se usa el cliente oficial NetBird. La documentación vigente indica que Android no dispone hoy de canal MDM para entregar políticas/Setup Keys. Por tanto, `DevicePolicyManager.setApplicationRestrictions(..., setupKey)` no configura NetBird Android.
- El alta con Setup Key en Android se realiza desde la UI oficial (Change Server > Add this device with a setup key), o mediante `netbird up --setup-key` sólo si existe un binario CLI local y el dispositivo tiene root.
- El Setup Key nunca se incrusta en el APK, repositorio, BuildConfig, logs ni preferencias. Debe entregarse como secreto durante el enrolamiento y rotarse si se expuso.
- Device Owner puede solicitar Always-on VPN para el paquete VPN instalado, pero no registra el peer ni elimina el primer consentimiento/configuración de NetBird. La clase `NetBirdProvisioner` implementa este límite y no inventa una managed configuration.
- La detección de red comprueba el transporte VPN y direcciones IPv4 del rango 100.64.0.0/10; no depende exclusivamente del nombre `tun0` (el nombre de interfaz no es una API estable).

## ADB

- `NativeAdbClient` usa DADB y un par de claves persistentes por instalación en `filesDir/adb`. La clave privada nunca se comparte entre administradores.
- ADB legacy sobre TCP 5555 sólo funciona si el dispositivo remoto expone ese daemon y ya autorizó la clave pública. Android 11+ Wireless Debugging suele usar emparejamiento TLS y puertos dinámicos: no es equivalente a 5555 y requiere un flujo de pairing distinto.
- La autorización persistente depende de que el dispositivo remoto conserve la clave autorizada. Reinstalar/borrar datos de la app controladora cambia la identidad ADB.
- ADB no es un canal MDM universal: comandos como `dpm set-device-owner`, `reboot`, `pm install` o cambios Wi-Fi dependen de versión Android, permisos shell/root y estado de aprovisionamiento. Se muestran las salidas reales y no se promete éxito.

## Device Owner y ajustes de sistema

- `dpm set-device-owner` debe ejecutarse en un equipo apto para aprovisionamiento, normalmente sin cuentas/usuarios ya configurados y antes de completar la configuración inicial. La app consulta `pm list users` y `dumpsys account` para advertir, pero el resultado de Android es la autoridad final.
- Es intencional no llamar a `Settings.Global.ADB_ENABLED` desde la app. Habilitar ADB no abre el puerto 5555, no empareja claves RSA y no sustituye la autorización del usuario.
- No existe una API pública de Device Owner para ejecutar arbitrariamente `dumpsys deviceidle whitelist +io.netbird.client`. La app puede abrir el panel del sistema para que el administrador ajuste la optimización de batería.
- La instalación silenciosa de APK sólo es viable con privilegios de sistema/Device Owner o root según el mecanismo. ADB install es posible si el daemon ADB remoto acepta la sesión.

## Estado de esta rama

Esta rama añade:
- `NetBirdProvisioner.kt` en Equipos: detección de overlay, enrolamiento CLI root si está instalado, apertura del cliente oficial, solicitud Always-on VPN y acceso a ajustes de batería.
- `RemoteDeviceOperations.kt` en Control: telemetría, preflight de Device Owner, acciones rápidas y ejecución concurrente limitada.

Ya existían en la rama base el cliente DADB, el almacenamiento de claves y Room para peers. Aún requiere integrar estas nuevas operaciones en la UI, sustituir las pantallas legacy por Compose, terminar el flujo de pairing TLS de Android 11+, revisar API/credenciales de gestión NetBird en un backend seguro y ejecutar CI/compilación en Android SDK. No se deben distribuir APK hasta que esos checks pasen.
