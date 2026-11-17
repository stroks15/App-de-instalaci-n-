import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';

export const dynamic = 'force-dynamic';

/**
 * Resuelve los dispositivos destino: uno, varios o todos.
 */
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
 * POST /api/notify
 * Envía una notificación push in-band a un equipo o a todos.
 * Body: { title, body, deviceId? | deviceIds? | all? }
 *
 * Nota: Fire OS (Fire Stick) NO tiene Google Play Services, por lo que FCM
 * no funciona ahí. Esta notificación "in-band" viaja por la tabla de tareas
 * y el agente la muestra como notificación local + overlay en TV (latencia ~5s).
 */
export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { title, body: message } = body;

    if (!title || !message) {
      return NextResponse.json({ error: 'title y body son requeridos' }, { status: 400 });
    }

    const deviceIds = await resolveDeviceIds(body);
    if (deviceIds.length === 0) {
      return NextResponse.json({ error: 'No hay dispositivos destino' }, { status: 400 });
    }

    const supabase = getSupabaseAdmin();
    const now = new Date().toISOString();
    const payload = JSON.stringify({ title, body: message });

    // Crear una tarea notify por cada dispositivo
    const rows = deviceIds.map((deviceId: string) => ({
      device_id: deviceId,
      type: 'notify',
      payload,
      status: 'queued',
      created_at: now,
      updated_at: now,
    }));

    const { error: taskError } = await (supabase.from('tasks') as any).insert(rows);
    if (taskError) throw taskError;

    // Historial en tabla notifications
    const notifRows = deviceIds.map((deviceId: string) => ({
      device_id: deviceId,
      title,
      body: message,
      status: 'sent',
      created_at: now,
    }));
    await (supabase.from('notifications') as any).insert(notifRows);

    return NextResponse.json({
      sent: deviceIds.length,
      devices: deviceIds,
      title,
      body: message,
    }, { status: 201 });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

/**
 * GET /api/notify
 * Historial de notificaciones enviadas.
 */
export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url);
    const limit = parseInt(searchParams.get('limit') || '50');

    const supabase = getSupabaseAdmin();
    const { data, error } = await supabase
      .from('notifications')
      .select('*')
      .order('created_at', { ascending: false })
      .limit(limit);

    if (error) throw error;
    return NextResponse.json({ notifications: data || [] });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}