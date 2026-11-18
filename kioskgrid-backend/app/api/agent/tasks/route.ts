import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { requireAgent } from '@/lib/agent-auth';

export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest): Promise<Response> {
  const auth = await requireAgent(request);
  if (auth.response) return auth.response;
  if (!auth.device) {
    return NextResponse.json({ error: 'Equipo no autenticado' }, { status: 401 });
  }

  const db = getSupabaseAdmin();
  const now = new Date().toISOString();

  // Expire queued install requests server-side as a backstop if the TV is offline.
  const { error: expiryError } = await (db.from('tasks') as any).update({
    status: 'expired',
    error: 'Solicitud cancelada: no fue aceptada en 48 horas.',
    updated_at: now
  }).eq('device_id', auth.device.id)
    .eq('type', 'install')
    .eq('status', 'queued')
    .not('expires_at', 'is', null)
    .lte('expires_at', now);

  if (expiryError) {
    return NextResponse.json({ error: 'No se pudieron caducar las instalaciones pendientes' }, { status: 500 });
  }

  const { data, error } = await (db.from('tasks') as any).select('*')
    .eq('device_id', auth.device.id)
    .eq('status', 'queued')
    .order('created_at', { ascending: true })
    .limit(25);

  if (error) {
    return NextResponse.json({ error: 'No se pudieron consultar las tareas' }, { status: 500 });
  }

  await (db.from('devices') as any)
    .update({ status: 'online', last_seen: new Date().toISOString() })
    .eq('id', auth.device.id);

  return NextResponse.json(data ?? []);
}

export async function PATCH(request: NextRequest): Promise<Response> {
  const auth = await requireAgent(request);
  if (auth.response) return auth.response;
  if (!auth.device) {
    return NextResponse.json({ error: 'Equipo no autenticado' }, { status: 401 });
  }

  let body: Record<string, unknown>;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: 'JSON inválido' }, { status: 400 });
  }

  const id = typeof body.id === 'string' ? body.id : '';
  const status = typeof body.status === 'string' ? body.status : '';
  if (!id || !['in_progress', 'awaiting_user', 'completed', 'failed', 'expired'].includes(status)) {
    return NextResponse.json({ error: 'Actualización de tarea inválida' }, { status: 400 });
  }

  const db = getSupabaseAdmin();
  const persistedStatus = status === 'completed' ? 'done' : status;
  const { data, error } = await ((db.from('tasks') as any) as any).update({
    status: persistedStatus,
    result: typeof body.result === 'string' ? body.result.slice(0, 100000) : null,
    error: typeof body.error === 'string' ? body.error.slice(0, 2000) : null,
    updated_at: new Date().toISOString()
  }).eq('id', id)
    .eq('device_id', auth.device.id)
    .select('id,device_id,status,updated_at')
    .maybeSingle();

  if (error) {
    return NextResponse.json({ error: 'No se pudo actualizar la tarea' }, { status: 500 });
  }
  if (!data) {
    return NextResponse.json({ error: 'Tarea no encontrada para este equipo' }, { status: 404 });
  }

  return NextResponse.json({ task: data });
}
