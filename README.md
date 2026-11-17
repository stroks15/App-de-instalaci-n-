# Sistema KioskGrid — Dexterh4ck

Sistema completo de gestión remota de aplicaciones para dispositivos Android TV / Fire Stick / Android Box.

## Componentes

| Componente | Ruta | Descripción |
|------------|------|-------------|
| **Backend** | `kioskgrid-backend/` | API Next.js + Supabase (tareas, dispositivos, storage, pagos) |
| **Dexterh4ck Equipos** (App 1) | `dexterh4ck-equipos/` | Agente que se instala en cada TV Box / Fire Stick / Android TV |
| **Dexterh4ck Control** (App 2) | `dexterh4ck-control/` | Kiosko/controlador que va en tu teléfono o tablet |

## Flujo principal

1. **Dexterh4ck Equipos** se instala en cada dispositivo. Al abrirlo, configuras el **nombre del equipo** y la URL del servidor; el agente se registra en la tabla `devices` y empieza a escuchar comandos (polling cada 5s).
2. **Dexterh4ck Control** (en tu teléfono) lista los equipos vía `GET /api/devices`.
3. Seleccionas un equipo → puedes **instalar un APK** (lo subes a Supabase Storage vía `POST /api/storage` y se envía la URL al equipo), **desinstalar**, **abrir**, **fijar en launcher**, **reiniciar**, o **listar apps**.
4. También puedes hacerlo **de forma masiva** (a todos los equipos) desde la pantalla de gestión masiva.
5. Los resultados vuelven al backend (tabla `tasks` con `status` y `result`) y el controlador los muestra en tiempo real.

## Requisitos previos

- Proyecto de Supabase creado (el usado: `jnrnbbkjcvxeplyljkyj.supabase.co`)
- Migraciones SQL ejecutadas (ver `kioskgrid-backend/supabase/migrations/`)
- Node.js 18+ para el backend
- Android Studio para compilar las APKs

## Primeros pasos

### 1. Backend
```bash
cd kioskgrid-backend
npm install
npm run dev        # http://localhost:3000
```

### 2. Supabase (una sola vez)
Ejecuta en el SQL Editor de Supabase, en orden:
- `001_create_coupons_table.sql`
- `002_create_users_table.sql`
- `003_create_devices_table.sql`
- `004_create_tasks_table.sql`

El bucket de Storage `apks` se crea automáticamente al subir el primer APK (o créalo manualmente en Storage → New bucket, público).

### 3. Apps Android (en Android Studio)
```bash
# App 1: agente para los equipos
cd dexterh4ck-equipos && ./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# App 2: controlador para tu teléfono
cd dexterh4ck-control && ./gradlew assembleDebug
```

### 4. Despliegue
1. Instala **Dexterh4ck Equipos** en cada TV Box / Fire Stick y configura nombre + URL del servidor.
2. Instala **Dexterh4ck Control** en tu teléfono.
3. ¡Gestiona tus equipos remotamente!

## Cupón DEXTERH4CK

El cupón `DEXTERH4CK` (creado en la migración 001) desbloquea la app como **versión pagada permanente** con 100% de descuento. Se valida en `PATCH /api/coupons` y se aplica en la creación de sesiones de pago Stripe (`POST /api/payments` con `couponCode: "DEXTERH4CK"`).

## Estado actual

- ✅ Backend compilando sin errores (`npm run build` ✓)
- ✅ 12 endpoints operativos
- ✅ Lazy init de Supabase (sin errores en build time)
- ✅ Proyectos Android completos (código fuente listo para compilar en Android Studio)
- ⏳ Ejecución de migraciones SQL en Supabase (pendiente, ver arriba)
- ⏳ Compilación de APKs (requiere Android Studio en tu máquina)
