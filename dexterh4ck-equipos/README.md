# Dexterh4ck Equipos — App 1 (Agente)

> **Nombre de la app:** Dexterh4ck Equipos
> **Paquete:** `com.dexterh4ck.equipos`
> **Se instala en:** Android Box, Fire Stick, Android TV

Agente que se instala en cada equipo (TV Box / Fire Stick / Android TV) y recibe comandos remotos desde **Dexterh4ck Control** a través del backend KioskGrid (Supabase).

## Funcionalidades

- ✅ Configurar **nombre del equipo** (visible desde Dexterh4ck Control)
- ✅ Configurar **URL del servidor** (backend desplegado)
- ✅ Mostrar **ID único** del dispositivo (para identificación)
- ✅ Servicio en segundo plano con polling cada 5 segundos
- ✅ **Instalar APKs** enviados desde el controlador (descarga desde Supabase Storage + PackageInstaller con UI de TV)
- ✅ **Desinstalar apps** (diálogo de confirmación en pantalla)
- ✅ **Abrir apps** instaladas
- ✅ **Listar apps instaladas** y reportarlas al backend
- ✅ **Reportar info del equipo**: modelo, Android, IP, RAM, almacenamiento, batería, apps
- ✅ **Fijar app en el launcher** (intents para Android TV Leanback y Fire TV)
- ✅ **Reiniciar equipo** (diálogo del sistema si no hay privilegios)
- ✅ Registro automático del equipo en el backend (tabla `devices`)
- ✅ Servicio reinicia automáticamente tras reinicio del equipo (BOOT_COMPLETED)
- ✅ Notificación persistente con estado del último comando

## Estructura

```
dexterh4ck-equipos/
├── app/src/main/java/com/dexterh4ck/equipos/
│   ├── data/
│   │   ├── AgentConfig.kt          # Config persistida (URL, nombre, ID)
│   │   ├── Task.kt                 # Modelo de tarea + TaskUpdate
│   │   ├── KioskApi.kt             # Retrofit: GET tareas, PATCH estado, POST devices
│   │   └── DeviceRegistration.kt   # Modelo de registro
│   ├── service/
│   │   ├── CommandService.kt       # Servicio: polling + ejecución de comandos
│   │   ├── InstallResultReceiver.kt# Resultado de PackageInstaller
│   │   └── BootReceiver.kt         # Reinicia servicio al encender el equipo
│   ├── ui/MainActivity.kt          # Configuración + info del equipo + apps
│   └── util/DeviceIdentity.kt      # ID único estable
├── app/src/main/res/
│   ├── layout/activity_main.xml    # UI para TV (mando)
│   ├── drawable/ic_launcher.xml    # Ícono vectorial
│   └── values/
└── AndroidManifest.xml
```

## Tipos de comandos remotos soportados

| type | payload | Resultado |
|------|---------|-----------|
| `install` | URL pública del APK (Supabase Storage) | Descarga + instala vía PackageInstaller |
| `uninstall` | nombre de paquete (ej: `com.spotify.tv`) | Diálogo de desinstalación en pantalla |
| `launch` | nombre de paquete | Abre la app |
| `list_apps` | vacío | JSON con todas las apps instaladas |
| `device_info` | vacío | JSON con modelo, Android, IP, RAM, almacenamiento, batería |
| `pin_app` | nombre de paquete | Intenta fijar la app arriba del launcher |
| `reboot` | vacío | Reinicia (o muestra diálogo de apagado) |

## Comunicación con el backend

```
Agente (equipo)                         Backend (Supabase/Next.js)
    │                                          │
    │  POST /api/devices (registro)            │
    │ ───────────────────────────────────────► │
    │                                          │
    │  GET /api/tasks?deviceId=X&status=queued │
    │ ───────────────────────────────────────► │
    │  ◄─── tareas pendientes ──────────────── │
    │                                          │
    │  (ejecuta: install/uninstall/launch/...) │
    │                                          │
    │  PATCH /api/tasks/{id} (estado+resultado)│
    │ ───────────────────────────────────────► │
```

**Base URL:** `https://jnrnbbkjcvxeplyljkyj.supabase.co/` (configurable en pantalla)

## Cómo compilar el APK

1. Abre Android Studio → **Open** → selecciona `/var/minis/workspace/dexterh4ck-equipos`
2. Sincroniza Gradle
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`

O por consola:
```bash
cd /var/minis/workspace/dexterh4ck-equipos
./gradlew assembleDebug
```

## Instalación en el equipo

1. Copia el APK al equipo (USB, red, ADB: `adb install app-debug.apk`)
2. En el equipo: **Ajustes → Seguridad → Orígenes desconocidos** (activar para esta app)
3. Abre "Dexterh4ck Equipos"
4. Configura la URL del servidor (ya viene precargada) y el nombre del equipo
5. Toca **Guardar y conectar**
6. El equipo aparece en Dexterh4ck Control

## Permisos

- `REQUEST_INSTALL_PACKAGES` — instalar APKs
- `QUERY_ALL_PACKAGES` — listar apps instaladas
- `DELETE_PACKAGES` / `CHANGE_COMPONENT_ENABLED_STATE` — gestión de paquetes
- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` — comunicación
- `WAKE_LOCK`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` — servicio en segundo plano
- `REBOOT` — solo efectivo si el equipo está rooteado o la app es de sistema (si no, se muestra el diálogo de apagado del sistema)

## Notas sobre el launcher

- **Android TV (Leanback):** tras instalar una app, esta aparece automáticamente en la fila de apps. Para fijarla arriba: seleccionar app → mantener → "Fijar". El comando `pin_app` intenta los intents conocidos del launcher.
- **Fire Stick (Fire OS):** habilita **Ajustes → Aplicaciones → Mostrar en pantalla de inicio** ("Apps on Home") para que las nuevas apps aparezcan arriba.
- El orden exacto de apps en el launcher del sistema no es modificable por apps de terceros sin privilegios de sistema (limitación de Android/Fire OS).

## Solución de problemas

- **No aparece en Dexterh4ck Control:** verifica WiFi en el equipo, que la URL del servidor sea correcta y que el backend esté corriendo.
- **No instala APKs:** activa "Instalar apps desconocidas" para Dexterh4ck Equipos en Ajustes → Seguridad.
- **El servicio muere:** en boxes chinas activa "Auto Start" en Ajustes → Batería.