import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { requireUser } from '@/lib/request-auth';

export const dynamic = 'force-dynamic';

async function resolveDeviceIds(body: any, ownerId: string): Promise<string[]> {
  const db = getSupabaseAdmin();
  const { data, error } = await (db.from('devices') as any).select('id,device_id').eq('owner_id', ownerId);
  if (error) throw error;
  const owned = data ?? [];
  if (body.all === true) return owned.map((d: any) => d.id);
  const refs = Array.isArray(body.deviceIds) ? body.deviceIds : body.deviceId ? [body.deviceId] : [];
  if (!refs.length) return [];
  const selected = owned.filter((d: any) => refs.includes(d.id) || refs.includes(d.device_id));
  if (selected.length !== new Set(refs).size) throw new Error('Uno o más equipos no pertenecen a esta cuenta');
  return selected.map((d: any) => d.id);
}

export async function POST(request: NextRequest) {
  const auth = await requireUser(request);
  if (auth.response || !auth.user) return auth.response;
  try {
    const body = await request.json();
    const title = typeof body.title === 'string' ? body.title.trim().slice(0, 120) : '';
    const message = typeof body.body === 'string' ? body.body.trim().slice(0, 1000) : '';
    if (!title || !message) return NextResponse.json({ error: 'title y body son requeridos' }, { status: 400 });
    const deviceIds = await resolveDeviceIds(body, auth.user.id);
    if (!deviceIds.length) return NextResponse.json({ error: 'No hay dispositivos destino' }, { status: 400 });
    const db = getSupabaseAdmin();
    const now = new Date().toISOString();
    const payload = JSON.stringify({ title, body: message });
    const rows = deviceIds.map((deviceId: string) => ({
      device_id: deviceId, type: 'notify', payload, status: 'queued',
      created_at: now, updated_at: now
    }));
    const { error } = await (db.from('tasks') as any).insert(rows);
    if (error) throw error;
    return NextResponse.json({ sent: deviceIds.length, title, body: message }, { status: 201 });
  } catch (error: any) {
    const message = error?.message || 'No se pudo enviar la notificación';
    return NextResponse.json({ error: message }, { status: message.includes('pertenecen') ? 403 : 500 });
  }
}

export async function GET(request: NextRequest) {
  const auth = await requireUser(request);
  if (auth.response || !auth.user) return auth.response;
  const limit = Math.min(Math.max(Number(new URL(request.url).searchParams.get('limit') || 50) || 50, 1), 100);
  const db = getSupabaseAdmin();
  const { data: devices, error: deviceError } = await (db.from('devices') as any).select('id').eq('owner_id', auth.user.id);
  if (deviceError) return NextResponse.json({ error: 'No se pudieron consultar los equipos' }, { status: 500 });
  const ids = (devices ?? []).map((d: any) => d.id);
  if (!ids.length) return NextResponse.json({ notifications: [] });
  const { data, error } = await (db.from('tasks') as any).select('id,device_id,payload,status,created_at,updated_at,error')
    .eq('type', 'notify').in('device_id', ids).order('created_at', { ascending: false }).limit(limit);
  if (error) return NextResponse.json({ error: 'No se pudo consultar el historial' }, { status: 500 });
  return NextResponse.json({ notifications: data ?? [] });
}
