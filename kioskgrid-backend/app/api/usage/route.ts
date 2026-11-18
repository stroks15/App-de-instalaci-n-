import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { requireUser } from '@/lib/request-auth';

export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
  const auth = await requireUser(request);
  if (auth.response || !auth.user) return auth.response;
  const search = new URL(request.url).searchParams;
  const deviceRef = search.get('deviceId');
  const hours = Math.min(Math.max(Number(search.get('hours') || 24) || 24, 1), 720);
  if (!deviceRef) return NextResponse.json({ error: 'deviceId es requerido' }, { status: 400 });

  const db = getSupabaseAdmin();
  const { data: devices, error: deviceError } = await (db.from('devices') as any)
    .select('id,device_id').eq('owner_id', auth.user.id);
  if (deviceError) return NextResponse.json({ error: 'No se pudo verificar el equipo' }, { status: 500 });
  const device = (devices ?? []).find((d: any) => d.id === deviceRef || d.device_id === deviceRef);
  if (!device) return NextResponse.json({ error: 'Equipo no encontrado' }, { status: 404 });

  const since = new Date(Date.now() - hours * 60 * 60 * 1000).toISOString();
  const { data, error } = await (db.from('tasks') as any).select('id,result,updated_at,created_at')
    .eq('device_id', device.id).eq('type', 'get_usage').eq('status', 'done')
    .gte('created_at', since).order('created_at', { ascending: false }).limit(1);
  if (error) return NextResponse.json({ error: 'No se pudo consultar el uso de aplicaciones' }, { status: 500 });
  const task = data?.[0];
  let usage: any[] = [];
  if (task?.result) { try { usage = JSON.parse(task.result); } catch { usage = []; } }
  return NextResponse.json({
    deviceId: device.device_id, hours, usage,
    reported_at: task?.updated_at ?? null,
    message: task ? undefined : 'Sin datos en el periodo solicitado. Envía una tarea get_usage al equipo primero.'
  });
}
