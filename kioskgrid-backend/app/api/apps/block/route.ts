import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { requireUser } from '@/lib/request-auth';

export const dynamic = 'force-dynamic';

async function resolveDeviceIds(body: any, ownerId: string): Promise<string[]> {
  const db = getSupabaseAdmin();
  const { data, error } = await (db.from('devices') as any)
    .select('id,device_id')
    .eq('owner_id', ownerId);
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
    const pkg = typeof body.package === 'string' ? body.package.trim() : '';
    const block = body.block !== false;
    if (!/^[A-Za-z0-9_.]+$/.test(pkg)) {
      return NextResponse.json({ error: 'Nombre de paquete inválido' }, { status: 400 });
    }
    const deviceIds = await resolveDeviceIds(body, auth.user.id);
    if (!deviceIds.length) return NextResponse.json({ error: 'No hay dispositivos destino' }, { status: 400 });

    const db = getSupabaseAdmin();
    const now = new Date().toISOString();
    const type = block ? 'block_app' : 'unblock_app';
    const rows = deviceIds.map((deviceId: string) => ({
      device_id: deviceId, type, payload: pkg, status: 'queued',
      created_at: now, updated_at: now
    }));
    const { error } = await (db.from('tasks') as any).insert(rows);
    if (error) throw error;
    return NextResponse.json({ package: pkg, blocked: block, devices: deviceIds.length }, { status: 201 });
  } catch (error: any) {
    const message = error?.message || 'No se pudo crear la tarea';
    return NextResponse.json({ error: message }, { status: message.includes('pertenecen') ? 403 : 500 });
  }
}
