import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { requireUser } from '@/lib/request-auth';

export const dynamic = 'force-dynamic';

type Context = { params: { deviceId: string; sessionId: string } };

export async function PATCH(request: NextRequest, { params }: Context) {
  const auth = await requireUser(request);
  if (auth.response || !auth.user) return auth.response;

  const db = getSupabaseAdmin();
  const { data, error } = await (db.from('remote_sessions') as any)
    .update({ status: 'ended', ended_at: new Date().toISOString() })
    .eq('id', params.sessionId)
    .eq('device_id', params.deviceId)
    .eq('owner_id', auth.user.id)
    .in('status', ['requested', 'active'])
    .select('id,device_id,status,ended_at').maybeSingle();

  if (error) return NextResponse.json({ error: 'No se pudo cerrar la sesión' }, { status: 500 });
  if (!data) return NextResponse.json({ error: 'Sesión no encontrada o ya finalizada' }, { status: 404 });

  await (db.from('device_audit_logs') as any).insert({
    device_id: params.deviceId, owner_id: auth.user.id, actor_id: auth.user.id,
    action: 'remote_session_ended', metadata: { session_id: data.id }
  });
  return NextResponse.json({ session: data });
}
