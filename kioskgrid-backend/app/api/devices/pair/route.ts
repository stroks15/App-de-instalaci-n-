import { createHash } from 'crypto';
import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { requireUser } from '@/lib/request-auth';
import { consumePairingAttempt, pairingRateLimitResponse } from '@/lib/pairing-rate-limit';

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest): Promise<Response> {
  const auth = await requireUser(request);
  if (auth.response || !auth.user) return auth.response;

  try {
    const body = await request.json();
    const code = String(body.pairing_code || '').trim().toUpperCase();
    const db = getSupabaseAdmin();
    const limited = await consumePairingAttempt(db, request, [
      `user:${auth.user.id}`,
      `code:${createHash('sha256').update(code).digest('hex')}`
    ]);
    if (!limited.allowed) {
      if ('unavailable' in limited) return NextResponse.json({ error: 'Protección de emparejamiento no disponible' }, { status: 503 });
      return pairingRateLimitResponse(limited.retryAfterSeconds);
    }

    if (!/^(?:[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{5}|[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8})$/.test(code)) {
      return NextResponse.json({ error: 'Código inválido' }, { status: 400 });
    }

    const now = new Date().toISOString();
    const { data, error } = await (db.from('devices') as any)
      .update({ owner_id: auth.user.id, paired_at: now, pairing_code: null, pairing_expires_at: null, updated_at: now })
      .eq('pairing_code', code)
      .is('owner_id', null)
      .gt('pairing_expires_at', now)
      .select('id,device_id,name,status,last_seen,kiosk_enabled')
      .maybeSingle();

    if (error) return NextResponse.json({ error: 'No se pudo vincular el equipo' }, { status: 500 });
    if (!data) return NextResponse.json({ error: 'Código incorrecto, expirado o ya vinculado' }, { status: 404 });

    const { error: auditError } = await (db.from('device_audit_logs') as any).insert({
      device_id: data.id, owner_id: auth.user.id, actor_id: auth.user.id, action: 'device_paired', metadata: {}
    });
    if (auditError) console.error('No se pudo registrar auditoría de pairing', auditError);

    return NextResponse.json({ device: data });
  } catch {
    return NextResponse.json({ error: 'Solicitud inválida' }, { status: 400 });
  }
}
