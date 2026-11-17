import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { requireUser } from '@/lib/request-auth';

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest) {
  const auth = await requireUser(request);
  if (auth.response || !auth.user) return auth.response;
  try {
    const body = await request.json();
    const code = String(body.pairing_code || '').trim().toUpperCase();
    const name = String(body.name || '').trim().slice(0, 80);
    if (!/^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{5}$/.test(code) || !name) {
      return NextResponse.json({ error: 'Código inválido o nombre vacío' }, { status: 400 });
    }
    const db = getSupabaseAdmin();
    const { data, error } = await db.from('devices').update({
      user_id: auth.user.id,
      name,
      paired_at: new Date().toISOString(),
      updated_at: new Date().toISOString()
    }).eq('pairing_code', code).is('user_id', null).select('id,device_id,name,status,last_seen,kiosk_enabled').maybeSingle();
    if (error) throw error;
    if (!data) return NextResponse.json({ error: 'Código no encontrado, expirado o ya vinculado' }, { status: 404 });
    return NextResponse.json({ device: data }, { status: 200 });
  } catch (error) {
    console.error('POST /api/devices/pair', error);
    return NextResponse.json({ error: 'No se pudo vincular el equipo' }, { status: 500 });
  }
}
