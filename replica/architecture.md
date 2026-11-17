# Architecture: KioskGrid Clone

## 1. The Stack
- **Web App:** Next.js (App Router) + TypeScript. (Strong ecosystem for web consoles).
- **Styling:** Tailwind CSS. (Fast for admin UI).
- **Database:** Supabase (Postgres). (Managed, great for Auth & RLS).
- **ORM:** Drizzle. (Type-safe SQL).
- **Auth:** Supabase Auth. (Email/Password & social providers).
- **Payments:** Stripe. (Standard for SaaS subscriptions).
- **Hosting:** Vercel. (Simple deployment).
- **Files:** Supabase Storage. (For APK uploads).

## 2. The Schema (SQL)
```sql
-- Devices
create table devices (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  android_version text,
  status text not null default 'offline' check (status in ('online', 'offline')),
  last_seen timestamptz default now(),
  created_at timestamptz default now()
);

-- Tasks
create table tasks (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references devices(id) on delete cascade,
  type text not null, -- 'install_apk', 'restart', 'set_kiosk', 'factory_reset'
  payload jsonb default '{}',
  status text not null default 'queued' check (status in ('queued', 'in_progress', 'done', 'failed')),
  created_at timestamptz default now()
);

-- Kiosk Configurations
create table kiosk_configs (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references devices(id) on delete cascade,
  content_url text,
  orientation text check (orientation in ('landscape', 'portrait')),
  is_home_app boolean default false,
  updated_at timestamptz default now()
);

-- Indexes
create index idx_devices_owner on devices(owner_id);
create index idx_tasks_device on tasks(device_id, status);
```

## 3. The API (Core Routes)
- `POST /api/devices`: List/Create device pairing.
- `GET /api/devices/:id/tasks`: List pending commands.
- `POST /api/tasks`: Enqueue remote action (install APK, etc.).
- `PATCH /api/kiosk-configs/:id`: Update kiosk mode settings.

## 4. The Parts That Bite
- **Connectivity:** Devices are often offline; task queue must be robust.
- **Android Agent:** The "black box" agent must report state changes reliably via polling or long polling.
- **Concurrency:** Handling massive simultaneous task updates from fleet.

## 5. Build Order
1. **Vertical slice:** Auth + Add Device (pairing) + Send command (restart).
2. **Kiosk core:** Configure Kiosk mode (Web/Video).
3. **Operations:** Files and Screencap.
4. **Billing:** Stripe integration.
