import { NextRequest, NextResponse } from 'next/server';
import { getSupabase, getSupabaseAdmin } from '@/lib/supabase';
import { requireUser } from '@/lib/request-auth';

export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
  const auth = await requireUser(request);
  if (auth.response || !auth.user) return auth.response;
  const { searchParams } = new URL(request.url);
  const status = searchParams.get('status');
  const supabase = getSupabaseAdmin();
  let query = supabase.from('devices').select('*').eq('user_id', auth.user.id);

  if (status) {
    query = query.eq('status', status);
  }

  const { data, error } = await query.order('last_seen', { ascending: false });

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  return NextResponse.json(data);
}

export async function POST(request: NextRequest) {
  const body = await request.json();
  const { device_id, hardware_id, pairing_code, name, type, platform, app_version, status } = body;

  if (!device_id || !name) {
    return NextResponse.json({ error: 'device_id y name son requeridos' }, { status: 400 });
  }

  const supabase = await getSupabase();
  const { data, error } = await (supabase.from('devices') as any).upsert({
    device_id,
    hardware_id: hardware_id || device_id,
    pairing_code: pairing_code || null,
    name,
    type: type || 'android_tv',
    platform: platform || 'android',
    app_version: app_version || '1.0.0',
    status: status || 'online',
    last_seen: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  }, { onConflict: 'device_id' });

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  return NextResponse.json(data);
}