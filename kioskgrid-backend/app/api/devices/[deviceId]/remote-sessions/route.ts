import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { requireUser } from '@/lib/request-auth';

export const dynamic = 'force-dynamic';

type Context = { params: { deviceId: string } };

export async function GET(request: NextRequest, { params }: Context) {
  const auth = await requireUser(request);
  if (auth.response || !auth.user) return auth.response;

  const db = getSupabaseAdmin();
  const { data: device, error: deviceError } = await db
    .from('devices').select('id').eq('id', params.deviceId)
    .eq('owner_id', auth.user.id).maybeSingle();
  if (deviceError) return NextResponse.json({ error: 'No se pudo verificar el equipo' }, { status: 500 });
  if (!device) return NextResponse.json({ error: 'Equipo no encontrado' }, { status: 404 });

  const { data, error } = await db.from('remote_sessions')
    .select('id,device_id,status,capabilities,created_at,started_at,expires_at,ended_at')
    .eq('device_id', params.deviceId).eq('owner_id', auth.user.id)
    .order('created_at', { ascending: false }).limit(20);
  if (error) return NextResponse.json({ error: 'No se pudieron consultar las sesiones' }, { status: 500 });
  return NextResponse.json({ sessions: data ?? [] });
}

export async function POST(request: NextRequest, { params }: Context) {
  const auth = await requireUser(request);
  if (auth.response || !auth.user) return auth.response;

  const db = getSupabaseAdmin();
  const { data: device, error: deviceError } = await db
    .from('devices').select('id,owner_id,status').eq('id', params.deviceId)
    .eq('owner_id', auth.user.id).maybeSingle();
  if (deviceError) return NextResponse.json({ error: 'No se pudo verificar el equipo' }, { status: 500 });
  if (!device) return NextResponse.json({ error: 'Equipo no encontrado' }, { status: 404 });
  if (device.status !== 'online') return NextResponse.json({ error: 'El equipo está desconectado' }, { status: 409 });

  const { data: existing } = await db.from('remote_sessions')
    .select('id,status').eq('device_id', device.id)
    .in('status', ['requested', 'active']).maybeSingle();
  if (existing) return NextResponse.json({ error: 'Ya existe una sesión remota abierta', session: existing }, { status: 409 });

  const { data, error } = await db.from('remote_sessions').insert({
    device_id: device.id,
    owner_id: auth.user.id,
    status: 'requested',
    capabilities: { screen: true, input: false, transport: 'webrtc_pending' },
    expires_at: new Date(Date.now() + 5 * 60 * 1000).toISOString()
  }).select('id,device_id,status,capabilities,created_at,expires_at').single();
  if (error) return NextResponse.json({ error: 'No se pudo solicitar la sesión remota' }, { status: 500 });

  await db.from('device_audit_logs').insert({
    device_id: device.id, owner_id: auth.user.id, actor_id: auth.user.id,
    action: 'remote_session_requested', metadata: { session_id: data.id }
  });
  return NextResponse.json({
    session: data,
    message: 'Solicitud creada. El equipo debe aceptar la captura de pantalla en su propia pantalla antes de transmitir.'
  }, { status: 201 });
}
