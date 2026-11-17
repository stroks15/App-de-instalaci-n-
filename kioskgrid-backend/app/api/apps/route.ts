import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';

export const dynamic = 'force-dynamic';

/**
 * GET /api/apps?deviceId=XXX
 * Obtiene la lista de apps instaladas reportada por el agente.
 * El agente guarda el resultado en la tabla tasks (type=list_apps) o devices.
 * Aquí leemos el último resultado de la tarea list_apps del dispositivo.
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
      .from('tasks')
      .select('*')
      .eq('device_id', deviceId)
      .eq('type', 'list_apps')
      .eq('status', 'completed')
      .order('created_at', { ascending: false })
      .limit(1);

    if (error) throw error;

    if (!data || data.length === 0) {
      return NextResponse.json({
        deviceId,
        apps: [],
        updated_at: null,
        message: 'No hay información de apps. Envía una tarea list_apps al dispositivo.',
      });
    }

    const task = data[0] as any;
    let apps: any[] = [];
    try {
      apps = task?.result ? JSON.parse(task.result) : [];
    } catch {
      apps = [];
    }

    return NextResponse.json({
      deviceId,
      apps,
      updated_at: task?.updated_at || null,
      task_id: task?.id || null,
    });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}