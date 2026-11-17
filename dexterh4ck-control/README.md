# Dexterh4ck Control — App 2 (Kiosko / Controlador)

> **Nombre de la app:** Dexterh4ck Control
> **Paquete:** `com.dexterh4ck.control`
> **Se instala en:** tu teléfono o tablet

App de control remoto para gestionar todos tus equipos (Android Box, Fire Stick, Android TV) que ejecutan **Dexterh4ck Equipos**.

## Funcionalidades

- ✅ Visualizar todos los equipos conectados (estado online/offline, última conexión)
- ✅ Seleccionar un equipo y ver su información detallada
- ✅ **Instalar APK** desde tu almacenamiento:
  1. Seleccionas el APK desde tu teléfono
  2. La app lo sube a **Supabase Storage** (bucket `apks`) vía backend
  3. Envía la URL pública al equipo
  4. El equipo la descarga e instala (con confirmación en pantalla)
  5. Ves el progreso y resultado en tiempo real (polling de tareas)
- ✅ **Desinstalar apps** del equipo (una por una)
- ✅ **Abrir apps** instaladas en el equipo
- ✅ **Fijar apps** en el launcher del equipo
- ✅ **Actualizar lista de apps** instaladas (comando remoto `list_apps`)
- ✅ **Reiniciar** el equipo remotamente
- ✅ **Instalación masiva**: un APK a TODOS los equipos a la vez
- ✅ **Desinstalación masiva**: un paquete de TODOS los equipos
- ✅ Eliminar equipos de la lista
- ✅ Refresco automático cada 15 segundos

## Estructura

```
dexterh4ck-control/
├── app/src/main/java/com/dexterh4ck/control/
│   ├── data/
│   │   ├── ApiConfig.kt            # URL base del backend
│   │   ├── Device.kt               # Modelo de equipo
│   │   ├── DeviceDetail.kt         # Detalle + InstalledApp + TaskDetail
│   │   ├── Task.kt                 # Task + TaskRequest
│   │   ├── UploadResponse.kt       # Respuesta de subida a Storage
│   │   ├── AppsResponse.kt         # Respuesta de /api/apps
│   │   └── KioskApi.kt             # Retrofit: devices, tasks, apps, storage
│   ├── adapter/
│   │   ├── DeviceAdapter.kt        # Lista de equipos
│   │   └── InstalledAppAdapter.kt  # Apps instaladas (abrir/desinstalar/fijar)
│   └── ui/
│       ├── DeviceListActivity.kt   # Pantalla principal
│       ├── DeviceDetailActivity.kt # Gestión de un equipo (subida real a Storage)
│       ├── BulkManagementActivity.kt # Instalar/desinstalar en todos
│       └── ApkPickerActivity.kt    # Selector de APKs
└── app/src/main/res/
```

## Flujo de instalación remota (APK)

```
Tu teléfono                    Backend (Supabase)              Equipo TV
    │                               │                             │
    │  1. POST /api/storage         │                             │
    │  (multipart APK) ────────────►│  guarda en bucket "apks"    │
    │  ◄─── url pública ────────────│                             │
    │                               │                             │
    │  2. POST /api/tasks           │                             │
    │  {device_id, install, url} ──►│  INSERT tasks (queued)      │
    │                               │                             │
    │                               │  ◄── polling GET /api/tasks │
    │                               │  ──── tarea ───────────────►│
    │                               │                             │  descarga APK
    │                               │                             │  PackageInstaller
    │                               │  ◄── PATCH estado+resultado │
    │  3. GET /api/tasks/{id}       │                             │
    │  ◄── completed + resultado ───│                             │
```

## Cómo compilar el APK

1. Abre Android Studio → **Open** → `/var/minis/workspace/dexterh4ck-control`
2. Sincroniza Gradle
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. APK en `app/build/outputs/apk/debug/app-debug.apk`

O por consola:
```bash
cd /var/minis/workspace/dexterh4ck-control
./gradlew assembleDebug
```

## Requisitos

- Android 7.0+ (API 24+)
- Conexión a Internet
- El backend KioskGrid desplegado y las tablas migradas en Supabase

## Endpoints del backend que usa

| Endpoint | Uso |
|----------|-----|
| `GET /api/devices` | Listar equipos |
| `GET /api/devices/{id}` | Detalle + apps del equipo |
| `PUT /api/devices/{id}` | Actualizar equipo |
| `DELETE /api/devices/{id}` | Eliminar equipo |
| `GET /api/tasks` | Tareas (filtros) |
| `POST /api/tasks` | Enviar comando al equipo |
| `GET /api/tasks/{id}` | Estado de una tarea |
| `GET /api/apps?deviceId=X` | Apps instaladas reportadas |
| `POST /api/storage` | Subir APK a Supabase Storage |
| `GET /api/health` | Estado del backend |

## Notas

- La subida del APK se hace al **backend** (`POST /api/storage`), que lo guarda en el bucket `apks` de Supabase Storage (se crea automáticamente) y devuelve la URL pública.
- El equipo **descarga** la APK de esa URL y la instala localmente.
- La instalación en el equipo requiere confirmación en pantalla del propio equipo (diálogo del sistema de Android TV / Fire OS).
- El orden de apps en el launcher del sistema tiene limitaciones (ver README de dexterh4ck-equipos).