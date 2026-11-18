import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { requireAgent } from '@/lib/agent-auth';

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest): Promise<Response> {
  const auth = await requireAgent(request);
  if (auth.response || !auth.device) return auth.response;
  try {
    const body = await request.json().catch(() => ({}));
    const name = typeof body.name === 'string' ? body.name.trim().slice(0, 80) : '';
    const db = getSupabaseAdmin();
    const update: Record<string, unknown> = {
      status: 'online',
      last_seen: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    };
    if (name) {
      update.name = name;
      update.device_name = name;
    }
    const { error } = await db.from('devices').update(update).eq('id', auth.device.id);
    if (error) {
      console.error('Agent heartbeat update failed:', error);
      return NextResponse.json({ error: 'No se pudo actualizar el heartbeat' }, { status: 500 });
    }
    return NextResponse.json({
      ok: true,
      device_id: auth.device.device_id,
      status: 'online',
      last_seen: update.last_seen,
    }, { headers: { 'Cache-Control': 'no-store' } });
  } catch (error) {
    console.error('POST /api/agent/heartbeat', error);
    return NextResponse.json({ error: 'Heartbeat inválido' }, { status: 400 });
  }
}
