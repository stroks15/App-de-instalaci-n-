import { NextRequest, NextResponse } from 'next/server';
import { createHash } from 'crypto';
import { getSupabaseAdmin } from '@/lib/supabase';

export async function requireAgent(request: NextRequest) {
  const authorization = request.headers.get('authorization') || '';
  const token = authorization.startsWith('Bearer ') ? authorization.slice(7).trim() : '';
  if (!token || token.length < 32) {
    return { device: null, response: NextResponse.json({ error: 'Se requiere credencial del equipo' }, { status: 401 }) };
  }
  try {
    const hash = createHash('sha256').update(token).digest('hex');
    const db = getSupabaseAdmin();
    const { data, error } = await db.from('devices')
      .select('id,device_id,owner_id,status')
      .eq('agent_key_hash', hash)
      .maybeSingle();
    if (error || !data) {
      return { device: null, response: NextResponse.json({ error: 'Credencial del equipo inválida' }, { status: 401 }) };
    }
    return { device: data, response: null };
  } catch {
    return { device: null, response: NextResponse.json({ error: 'Autenticación del equipo no disponible' }, { status: 503 }) };
  }
}

export function hashAgentToken(token: string) {
  return createHash('sha256').update(token).digest('hex');
}
