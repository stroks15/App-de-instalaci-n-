import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';

export const dynamic = 'force-dynamic';

/**
 * GET /api/devices/[deviceId]
 * Obtiene la información detallada de un dispositivo: datos del equipo,
 * últimas tareas y últimas apps reportadas.
 */
export async function GET(
  request: NextRequest,
  { params }: { params: { deviceId: string } }
) {
  try {
    const deviceId = params.deviceId;
    const supabase = getSupabaseAdmin();

    // Datos del dispositivo
    const { data: device, error: deviceError } = await supabase
      .from('devices')
      .select('*')
      .eq('device_id', deviceId)
      .single();

    if (deviceError || !device) {
      return NextResponse.json({ error: 'Dispositivo no encontrado' }, { status: 404 });
    }

    // Últimas 20 tareas del dispositivo
    const { data: tasks } = await supabase
      .from('tasks')
      .select('*')
      .eq('device_id', deviceId)
      .order('created_at', { ascending: false })
      .limit(20);

    // Última lista de apps reportada
    const { data: appTask } = await supabase
      .from('tasks')
      .select('*')
      .eq('device_id', deviceId)
      .eq('type', 'list_apps')
      .eq('status', 'completed')
      .order('created_at', { ascending: false })
      .limit(1);

    const lastAppTask = (appTask as any[] || [])[0];
    let installedApps: any[] = [];
    if (lastAppTask && lastAppTask.result) {
      try {
        installedApps = JSON.parse(lastAppTask.result);
      } catch {
        installedApps = [];
      }
    }

    return NextResponse.json({
      device,
      tasks: tasks || [],
      installed_apps: installedApps,
      apps_updated_at: lastAppTask?.updated_at || null,
    });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

/**
 * PUT /api/devices/[deviceId]
 * Actualiza nombre, estado o información del dispositivo.
 */
export async function PUT(
  request: NextRequest,
  { params }: { params: { deviceId: string } }
) {
  try {
    const deviceId = params.deviceId;
    const body = await request.json();

    const allowedUpdates = ['name', 'status', 'type', 'platform', 'app_version', 'last_seen'];
    const updates: Record<string, any> = { updated_at: new Date().toISOString() };
    for (const key of allowedUpdates) {
      if (key in body) updates[key] = body[key];
    }

    const supabase = getSupabaseAdmin();
    const { data, error } = await (supabase.from('devices') as any)
      .update(updates)
      .eq('device_id', deviceId)
      .select()
      .single();

    if (error) throw error;
    return NextResponse.json({ device: data });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

/**
 * DELETE /api/devices/[deviceId]
 * Elimina un dispositivo y sus tareas asociadas.
 */
export async function DELETE(
  request: NextRequest,
  { params }: { params: { deviceId: string } }
) {
  try {
    const deviceId = params.deviceId;
    const supabase = getSupabaseAdmin();
    const { error: taskError } = await (supabase.from('tasks') as any).delete().eq('device_id', deviceId);
    if (taskError) throw taskError;

    const { error } = await (supabase.from('devices') as any).delete().eq('device_id', deviceId);
    if (error) throw error;

    return NextResponse.json({ message: 'Dispositivo eliminado correctamente' });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}