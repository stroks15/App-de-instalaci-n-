import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { requireUser } from '@/lib/request-auth';

export const dynamic = 'force-dynamic';
const MAX_IMAGE_CHARS = 12 * 1024 * 1024;

export async function POST(request: NextRequest) {
  const auth = await requireUser(request);
  if (auth.response || !auth.user) return auth.response;
  try {
    const body = await request.json();
    const deviceRef = typeof body.deviceId === 'string' ? body.deviceId : '';
    const type = typeof body.type === 'string' ? body.type.trim().slice(0, 40) : '';
    const fileName = typeof body.fileName === 'string' ? body.fileName.replace(/[^a-zA-Z0-9._-]/g, '_').slice(0, 120) : 'screen.png';
    const fileContent = typeof body.fileContent === 'string' ? body.fileContent : '';
    if (!deviceRef || !type || !fileContent || fileContent.length > MAX_IMAGE_CHARS) {
      return NextResponse.json({ error: 'deviceId, type y una imagen válida son requeridos' }, { status: 400 });
    }
    const db = getSupabaseAdmin();
    const { data: devices, error: deviceError } = await (db.from('devices') as any)
      .select('id,device_id').eq('owner_id', auth.user.id);
    if (deviceError) return NextResponse.json({ error: 'No se pudo verificar el equipo' }, { status: 500 });
    const device = (devices ?? []).find((d: any) => d.id === deviceRef || d.device_id === deviceRef);
    if (!device) return NextResponse.json({ error: 'Equipo no encontrado' }, { status: 404 });
    const { data, error } = await (db.from('tasks') as any).insert({
      device_id: device.id,
      type: `screencap:${encodeURIComponent(fileName)}`,
      payload: { image: fileContent, requested_type: type },
      status: 'queued',
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString()
    }).select('id,device_id,type,status,created_at').single();
    if (error) return NextResponse.json({ error: 'No se pudo guardar la captura' }, { status: 500 });
    return NextResponse.json({ task: data }, { status: 201 });
  } catch {
    return NextResponse.json({ error: 'Solicitud de captura inválida' }, { status: 400 });
  }
}

export async function GET(request: NextRequest) {
  const auth = await requireUser(request);
  if (auth.response || !auth.user) return auth.response;
  const params = new URL(request.url).searchParams;
  const deviceRef = params.get('deviceId');
  const type = params.get('type');
  const db = getSupabaseAdmin();
  const { data: devices, error: deviceError } = await (db.from('devices') as any)
    .select('id,device_id').eq('owner_id', auth.user.id);
  if (deviceError) return NextResponse.json({ error: 'No se pudieron consultar los equipos' }, { status: 500 });
  let ids = (devices ?? []).map((d: any) => d.id);
  if (deviceRef) {
    const device = (devices ?? []).find((d: any) => d.id === deviceRef || d.device_id === deviceRef);
    if (!device) return NextResponse.json({ error: 'Equipo no encontrado' }, { status: 404 });
    ids = [device.id];
  }
  if (!ids.length) return NextResponse.json([]);
  let query = (db.from('tasks') as any).select('*').in('device_id', ids);
  if (type) query = query.eq('type', type);
  const { data, error } = await query.order('created_at', { ascending: false }).limit(100);
  if (error) return NextResponse.json({ error: 'No se pudieron consultar las capturas' }, { status: 500 });
  return NextResponse.json(data ?? []);
}
