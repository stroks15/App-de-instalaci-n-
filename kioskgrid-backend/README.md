# KioskGrid Backend

Backend en **Next.js 14 (App Router)** que conecta las dos apps Android con **Supabase** (PostgreSQL + Auth + Storage).

## Endpoints

### Salud
| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/api/health` | Estado del backend y conexión a Supabase |

### Dispositivos
| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/api/devices` | Listar equipos (filtro `?status=online`) |
| POST | `/api/devices` | Registrar/actualizar equipo (desde el agente) |
| GET | `/api/devices/{deviceId}` | Detalle: datos + últimas tareas + apps instaladas |
| PUT | `/api/devices/{deviceId}` | Actualizar nombre/estado |
| DELETE | `/api/devices/{deviceId}` | Eliminar equipo y sus tareas |

### Tareas (comandos remotos)
| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/api/tasks` | Listar (filtros: `deviceId`, `status`, `type`, `limit`) |
| POST | `/api/tasks` | Crear tarea (desde el controlador) o reportar resultado si trae `id` |
| PATCH | `/api/tasks` | Actualizar estado/resultado de una tarea |
| GET | `/api/tasks/{taskId}` | Detalle de una tarea (para tracking) |
| PATCH | `/api/tasks/{taskId}` | Actualizar estado/resultado (desde el agente) |
| DELETE | `/api/tasks/{taskId}` | Eliminar tarea |

**Tipos de tarea:** `install` (payload=URL APK), `uninstall` (payload=paquete), `launch` (payload=paquete), `list_apps`, `device_info`, `reboot`, `pin_app` (payload=paquete)

### Aplicaciones
| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/api/apps?deviceId=X` | Apps instaladas reportadas por el agente (última tarea `list_apps` completada) |

### Almacenamiento (APKs en Supabase Storage)
| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/api/storage?bucket=apks&path=` | Listar archivos del bucket |
| POST | `/api/storage` | Subir archivo (multipart: `file`, `bucket` opcional). **Crea el bucket `apks` automáticamente** y devuelve `{path, url}` |
| DELETE | `/api/storage?bucket=apks&path=` | Eliminar archivo |

### Cupones / Pagos
| Método | Ruta | Descripción |
|--------|------|-------------|
| GET | `/api/coupons` | Listar cupones |
| POST | `/api/coupons` | Crear cupón |
| PUT | `/api/coupons` | Actualizar cupón |
| DELETE | `/api/coupons?id=` | Eliminar cupón |
| PATCH | `/api/coupons` | Validar cupón (`{"code":"DEXTERH4CK"}`) |
| GET | `/api/payments` | Planes disponibles |
| POST | `/api/payments` | Crear sesión de pago Stripe (con soporte de cupón) |
| POST | `/api/webhooks/stripe` | Webhook Stripe (otorga acceso permanente) |

### Otros
| Método | Ruta | Descripción |
|--------|------|-------------|
| GET/POST | `/api/files/screencap` | Capturas de pantalla por tarea |
| GET | `/` | Página informativa con los endpoints |

## Variables de entorno

Copia `.env.example` a `.env.local`:

```
NEXT_PUBLIC_SUPABASE_URL=https://tu-proyecto.supabase.co
NEXT_PUBLIC_SUPABASE_ANON_KEY=...
SUPABASE_SERVICE_ROLE_KEY=...
STRIPE_SECRET_KEY=...
STRIPE_WEBHOOK_SECRET=...
```

## Migraciones SQL (ejecutar en Supabase → SQL Editor)

1. `supabase/migrations/001_create_coupons_table.sql` — tabla `coupons` + cupón **DEXTERH4CK** (100% descuento, acceso permanente)
2. `supabase/migrations/002_create_users_table.sql` — tabla `users` + funciones `has_permanent_access` / `grant_permanent_access`
3. `supabase/migrations/003_create_devices_table.sql` — tabla `devices` (equipos registrados)
4. `supabase/migrations/004_create_tasks_table.sql` — tabla `tasks` (comandos remotos con campo `result`)

## Ejecución

```bash
npm install
npm run dev      # http://localhost:3000
npm run build    # compilación de producción
npm start        # servir la compilación
```

## Arquitectura

```
Dexterh4ck Control (móvil)                Dexterh4ck Equipos (TV Box/Fire Stick)
        │                                              │
        │  POST /api/storage (sube APK)                │  GET /api/tasks?deviceId=X&status=queued
        │  POST /api/tasks (comando)                   │  PATCH /api/tasks/{id} (reporta resultado)
        ▼                                              ▼
┌───────────────────────────────────────────────────────────┐
│  Backend Next.js (este proyecto)                          │
│  - Lazy init de Supabase (sin errores en build)           │
│  - RLS en todas las tablas                                │
└───────────────────────────────────────────────────────────┘
                          │
                          ▼
              Supabase (Postgres + Auth + Storage)
              - devices, tasks, coupons, users
              - bucket "apks" (archivos APK públicos)
```