import { randomBytes } from 'crypto';
import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { hashAgentToken } from '@/lib/agent-auth';
import { consumePairingAttempt, pairingRateLimitResponse } from '@/lib/pairing-rate-limit';

export const dynamic = 'force-dynamic';
const PAIRING_TTL_MS = 10 * 60 * 1000;

export async function POST(request: NextRequest): Promise<Response> {
  try {
    const body = await request.json();
    const hardwareId = String(body.hardware_id || body.device_id || '').trim().slice(0, 128);
    const pairingCode = String(body.pairing_code || '').trim().toUpperCase();
    const name = String(body.name || 'Equipo TV').trim().slice(0, 80);
    if (!hardwareId || !/^(?:[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{5}|[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8})$/.test(pairingCode)) {
      return NextResponse.json({ error: 'Identificador o código de vinculación inválido' }, { status: 400 });
    }

    const db = getSupabaseAdmin();
    const now = new Date();
    const nowIso = now.toISOString();
    const expiresAt = new Date(now.getTime() + PAIRING_TTL_MS).toISOString();
    const { data: existing, error: lookupError } = await (db.from('devices') as any)
      .select('id,agent_key_hash,owner_id,pairing_code,pairing_expires_at')
      .eq('hardware_id', hardwareId).maybeSingle();
    if (lookupError) throw lookupError;

    const bearer = request.headers.get('authorization')?.replace(/^Bearer\s+/i, '').trim() || '';
    if (existing?.agent_key_hash) {
      const authorizedByToken = !!bearer && hashAgentToken(bearer) === existing.agent_key_hash;
      if (!authorizedByToken) {
        const limited = await consumePairingAttempt(db, request, [
          `device:${hardwareId}`,
        ]);
        if (!limited.allowed) {
          if ('unavailable' in limited) return NextResponse.json({ error: 'Protección de emparejamiento no disponible' }, { status: 503 });
          return pairingRateLimitResponse(limited.retryAfterSeconds);
        }
      }
      const codeIsCurrent = !existing.owner_id &&
        pairingCode === existing.pairing_code &&
        !!existing.pairing_expires_at &&
        new Date(existing.pairing_expires_at).getTime() > now.getTime();
      if (!authorizedByToken && !codeIsCurrent) {
        return NextResponse.json({ error: 'Este equipo ya está registrado; se requiere su credencial guardada o un código vigente de vinculación' }, { status: 401 });
      }

      const replacementToken = authorizedByToken ? null : randomBytes(32).toString('base64url');
      const { data, error } = await (db.from('devices') as any).update({
        name, status: 'online', last_seen: nowIso, updated_at: nowIso,
        ...(!existing.owner_id ? { pairing_expires_at: expiresAt } : {}),
        ...(replacementToken ? { agent_key_hash: hashAgentToken(replacementToken) } : {})
      }).eq('id', existing.id).select('id,device_id,name,status,last_seen,owner_id,pairing_expires_at').single();
      if (error) throw error;
      return NextResponse.json({ device: data, ...(replacementToken ? { agent_token: replacementToken } : {}), token_rotated: !!replacementToken });
    }

    if (existing) {
      const limited = await consumePairingAttempt(db, request, [
        `device:${hardwareId}`,
      ]);
      if (!limited.allowed) {
        if ('unavailable' in limited) return NextResponse.json({ error: 'Protección de emparejamiento no disponible' }, { status: 503 });
        return pairingRateLimitResponse(limited.retryAfterSeconds);
      }
      const current = existing.pairing_code === pairingCode &&
        !!existing.pairing_expires_at &&
        new Date(existing.pairing_expires_at).getTime() > now.getTime();
      if (!current) return NextResponse.json({ error: 'El código visible no coincide o ha expirado' }, { status: 401 });

      const recoveredToken = randomBytes(32).toString('base64url');
      const { data, error } = await (db.from('devices') as any).update({
        agent_key_hash: hashAgentToken(recoveredToken), name, status: 'online',
        last_seen: nowIso, updated_at: nowIso, pairing_expires_at: expiresAt
      }).eq('id', existing.id).select('id,device_id,name,status,last_seen,owner_id,pairing_expires_at').single();
      if (error) throw error;
      return NextResponse.json({ device: data, agent_token: recoveredToken, token_rotated: true });
    }

    const token = randomBytes(32).toString('base64url');
    const row = {
      hardware_id: hardwareId, device_id: hardwareId, pairing_code: pairingCode,
      pairing_expires_at: expiresAt, name,
      type: String(body.type || 'android_tv').slice(0, 40),
      platform: String(body.platform || 'android').slice(0, 30),
      app_version: String(body.app_version || '1.0.0').slice(0, 30),
      status: 'online', last_seen: nowIso, updated_at: nowIso,
      agent_key_hash: hashAgentToken(token)
    };
    const { data, error } = await (db.from('devices') as any).insert(row)
      .select('id,device_id,name,status,last_seen,owner_id,pairing_expires_at').single();
    if (error) throw error;
    return NextResponse.json({ device: data, agent_token: token }, { status: 201 });
  } catch (error) {
    console.error('POST /api/agent/register', error);
    return NextResponse.json({ error: 'No se pudo registrar el equipo' }, { status: 500 });
  }
}
