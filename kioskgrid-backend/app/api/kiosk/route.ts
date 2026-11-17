import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';

export const dynamic = 'force-dynamic';

async function resolveDeviceIds(body: any): Promise<string[]> {
  const supabase = getSupabaseAdmin();
  if (body.all === true) {
    const { data } = await supabase.from('devices').select('device_id');
    return (data || []).map((d: any) => d.device_id);
  }
  if (Array.isArray(body.deviceIds) && body.deviceIds.length > 0) {
    return body.deviceIds;
  }
  if (body.deviceId) {
    return [body.deviceId];
  }
  return [];
}

/**
 * POST /api/kiosk
 * Activa o desactiva el modo kiosk (bloqueo del equipo) en uno o varios dispositivos.
 * Body: { action: 'enable'|'disable', allowedApps?: "pkg1,pkg2", deviceId? | deviceIds? | all? }
 *
 * enable → tarea kiosk_enable (payload = CSV de paquetes permitidos; si va vacío, se permiten todas las apps del usuario)
 * disable → tarea kiosk_disable
 */
export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { action, allowedApps } = body;

    if (!['enable', 'disable'].includes(action)) {
      return NextResponse.json({ error: 'action debe ser enable o disable' }, { status: 400 });
    }

    const deviceIds = await resolveDeviceIds(body);
    if (deviceIds.length === 0) {
      return NextResponse.json({ error: 'No hay dispositivos destino' }, { status: 400 });
    }

    const supabase = getSupabaseAdmin();
    const now = new Date().toISOString();
    const type = action === 'enable' ? 'kiosk_enable' : 'kiosk_disable';

    const rows = deviceIds.map((deviceId: string) => ({
      device_id: deviceId,
      type,
      payload: action === 'enable' ? (allowedApps || '') : '',
      status: 'queued',
      created_at: now,
      updated_at: now,
    }));

    const { error: taskError } = await (supabase.from('tasks') as any).insert(rows);
    if (taskError) throw taskError;

    // Actualizar estado optimista en la tabla devices
    await (supabase.from('devices') as any)
      .update({ kiosk_enabled: action === 'enable', updated_at: now })
      .in('device_id', deviceIds);

    return NextResponse.json({
      action,
      devices: deviceIds.length,
      deviceIds,
      allowedApps: action === 'enable' ? (allowedApps || '(todas las apps del usuario)') : null,
    }, { status: 201 });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

/**
 * GET /api/kiosk?deviceId=XXX
 * Estado del modo kiosk de un equipo.
 */
export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url);
    const deviceId = searchParams.get('deviceId');
    if (!deviceId) {
      return NextResponse.json({ error: 'deviceId es requerido' }, { status: 400 });
    }

    const supabase = getSupabaseAdmin();
    const { data, error } = await supabase
      .from('devices')
      .select('kiosk_enabled, allowed_apps, blocked_apps')
      .eq('device_id', deviceId)
      .single();

    if (error || !data) {
      return NextResponse.json({ error: 'Dispositivo no encontrado' }, { status: 404 });
    }

    return NextResponse.json(data);
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}