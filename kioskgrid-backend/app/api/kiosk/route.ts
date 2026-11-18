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
    const { action, allowedApps } = body;
    const allowedPackages = (Array.isArray(allowedApps) ? allowedApps : typeof allowedApps === 'string' ? allowedApps.split(/[,\n]/) : [])
      .map((value: unknown) => String(value).trim())
      .filter((value: string) => value.length > 0);
    if (allowedPackages.some((pkg: string) => !/^[A-Za-z0-9_.]+$/.test(pkg))) {
      return NextResponse.json({ error: 'La lista de paquetes contiene un nombre inválido' }, { status: 400 });
    }
    if (!['enable', 'disable'].includes(action)) return NextResponse.json({ error: 'action debe ser enable o disable' }, { status: 400 });
    const deviceIds = await resolveDeviceIds(body, auth.user.id);
    if (!deviceIds.length) return NextResponse.json({ error: 'No hay dispositivos destino' }, { status: 400 });
    const db = getSupabaseAdmin();
    const now = new Date().toISOString();
    const type = action === 'enable' ? 'kiosk_enable' : 'kiosk_disable';
    const rows = deviceIds.map((deviceId: string) => ({
      device_id: deviceId, type, payload: action === 'enable' ? JSON.stringify({ allowedPackages }) : '',
      status: 'queued', created_at: now, updated_at: now
    }));
    const { error } = await (db.from('tasks') as any).insert(rows);
    if (error) throw error;
    await (db.from('devices') as any).update({ kiosk_enabled: action === 'enable', updated_at: now }).in('id', deviceIds);
    return NextResponse.json({ action, devices: deviceIds.length, allowedApps: action === 'enable' ? allowedPackages : null }, { status: 201 });
  } catch (error: any) {
    const message = error?.message || 'No se pudo cambiar el modo kiosk';
    return NextResponse.json({ error: message }, { status: message.includes('pertenecen') ? 403 : 500 });
  }
}

export async function GET(request: NextRequest) {
  const auth = await requireUser(request);
  if (auth.response || !auth.user) return auth.response;
  const ref = new URL(request.url).searchParams.get('deviceId');
  if (!ref) return NextResponse.json({ error: 'deviceId es requerido' }, { status: 400 });
  const db = getSupabaseAdmin();
  const { data, error } = await (db.from('devices') as any).select('id,device_id,kiosk_enabled').eq('owner_id', auth.user.id);
  if (error) return NextResponse.json({ error: 'No se pudo consultar el dispositivo' }, { status: 500 });
  const device = (data ?? []).find((d: any) => d.id === ref || d.device_id === ref);
  if (!device) return NextResponse.json({ error: 'Dispositivo no encontrado' }, { status: 404 });
  return NextResponse.json(device);
}
