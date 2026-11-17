import { randomBytes, createHash, timingSafeEqual } from 'crypto';
import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { hashAgentToken } from '@/lib/agent-auth';

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const hardwareId = String(body.hardware_id || body.device_id || '').trim().slice(0, 128);
    const pairingCode = String(body.pairing_code || '').trim().toUpperCase();
    const name = String(body.name || 'Equipo TV').trim().slice(0, 80);
    if (!hardwareId || !/^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{5}$/.test(pairingCode)) {
      return NextResponse.json({ error: 'Identificador o código de vinculación inválido' }, { status: 400 });
    }

    const db = getSupabaseAdmin();
    const { data: existing, error: lookupError } = await db.from('devices')
      .select('id,agent_key_hash,owner_id')
      .eq('hardware_id', hardwareId).maybeSingle();
    if (lookupError) throw lookupError;

    const bearer = request.headers.get('authorization')?.replace(/^Bearer\s+/i, '').trim() || '';
    if (existing?.agent_key_hash) {
      if (!bearer || hashAgentToken(bearer) !== existing.agent_key_hash) {
        return NextResponse.json({ error: 'Este equipo ya está registrado; se requiere su credencial guardada' }, { status: 401 });
      }
      const { data, error } = await db.from('devices').update({
        name, pairing_code, status: 'online', last_seen: new Date().toISOString(), updated_at: new Date().toISOString()
      }).eq('id', existing.id).select('id,device_id,name,status,last_seen,owner_id').single();
      if (error) throw error;
      return NextResponse.json({ device: data, token_rotated: false });
    }

    const token = randomBytes(32).toString('base64url');
    const row = {
      hardware_id: hardwareId,
      device_id: hardwareId,
      pairing_code,
      name,
      type: String(body.type || 'android_tv').slice(0, 40),
      platform: String(body.platform || 'android').slice(0, 30),
      app_version: String(body.app_version || '1.0.0').slice(0, 30),
      status: 'online',
      last_seen: new Date().toISOString(),
      updated_at: new Date().toISOString(),
      agent_key_hash: hashAgentToken(token)
    };
    const { data, error } = await db.from('devices').insert(row)
      .select('id,device_id,name,status,last_seen,owner_id').single();
    if (error) throw error;
    return NextResponse.json({ device: data, agent_token: token }, { status: 201 });
  } catch (error) {
    console.error('POST /api/agent/register', error);
    return NextResponse.json({ error: 'No se pudo registrar el equipo' }, { status: 500 });
  }
}
