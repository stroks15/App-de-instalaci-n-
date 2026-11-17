import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { requireAgent } from '@/lib/agent-auth';

export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
  const auth = await requireAgent(request);
  if (auth.response || !auth.device) return auth.response;
  const db = getSupabaseAdmin();
  const { data, error } = await db.from('tasks').select('*')
    .eq('device_id', auth.device.id).eq('status', 'queued')
    .order('created_at', { ascending: true }).limit(25);
  if (error) return NextResponse.json({ error: 'No se pudieron consultar las tareas' }, { status: 500 });
  await db.from('devices').update({ status: 'online', last_seen: new Date().toISOString() })
    .eq('id', auth.device.id);
  return NextResponse.json(data ?? []);
}

export async function PATCH(request: NextRequest) {
  const auth = await requireAgent(request);
  if (auth.response || !auth.device) return auth.response;
  const body = await request.json();
  const id = String(body.id || '');
  const status = String(body.status || '');
  if (!id || !['in_progress', 'completed', 'failed'].includes(status)) {
    return NextResponse.json({ error: 'Actualización de tarea inválida' }, { status: 400 });
  }
  const db = getSupabaseAdmin();
  const { data, error } = await (db.from('tasks') as any).update({
    status,
    result: typeof body.result === 'string' ? body.result.slice(0, 100000) : null,
    error: typeof body.error === 'string' ? body.error.slice(0, 2000) : null,
    updated_at: new Date().toISOString()
  }).eq('id', id).eq('device_id', auth.device.id)
    .select('id,device_id,status,updated_at').maybeSingle();
  if (error) return NextResponse.json({ error: 'No se pudo actualizar la tarea' }, { status: 500 });
  if (!data) return NextResponse.json({ error: 'Tarea no encontrada para este equipo' }, { status: 404 });
  return NextResponse.json({ task: data });
}
