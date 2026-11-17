import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';

export const dynamic = 'force-dynamic';

/**
 * GET /api/usage?deviceId=XXX&hours=24
 * Devuelve el último reporte de uso de apps del equipo (tarea get_usage completada).
 * El agente lo genera con UsageStatsManager.
 */
export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url);
    const deviceId = searchParams.get('deviceId');
    const hours = searchParams.get('hours') || '24';

    if (!deviceId) {
      return NextResponse.json({ error: 'deviceId es requerido' }, { status: 400 });
    }

    const supabase = getSupabaseAdmin();
    const { data, error } = await supabase
      .from('tasks')
      .select('*')
      .eq('device_id', deviceId)
      .eq('type', 'get_usage')
      .eq('status', 'completed')
      .order('created_at', { ascending: false })
      .limit(1);

    if (error) throw error;

    if (!data || data.length === 0) {
      return NextResponse.json({
        deviceId,
        hours,
        usage: [],
        message: 'Sin datos. Envía una tarea get_usage al equipo primero.',
      });
    }

    const task = data[0] as any;
    let usage: any[] = [];
    try {
      usage = task.result ? JSON.parse(task.result) : [];
    } catch {
      usage = [];
    }

    return NextResponse.json({
      deviceId,
      hours,
      usage,
      reported_at: task.updated_at,
    });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}