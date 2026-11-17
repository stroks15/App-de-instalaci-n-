import { NextRequest, NextResponse } from 'next/server';
import { supabase } from '@/lib/supabase';

export async function GET(request: NextRequest) {
  const { searchParams } = new URL(request.url);
  const deviceId = searchParams.get('deviceId');

  if (!deviceId) {
    return NextResponse.json({ error: 'deviceId requerido' }, { status: 400 });
  }

  const { data, error } = await supabase
    .from('kiosk_configs')
    .select('*')
    .eq('device_id', deviceId);

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  return NextResponse.json(data);
}

export async function POST(request: NextRequest) {
  const body = await request.json();
  const { deviceId, contentUrl, orientation, isHomeApp } = body;

  if (!deviceId) {
    return NextResponse.json({ error: 'deviceId requerido' }, { status: 400 });
  }

  const { data, error } = await supabase
    .from('kiosk_configs')
    .upsert([{ device_id: deviceId, content_url: contentUrl, orientation, is_home_app: isHomeApp }]);

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  return NextResponse.json(data);
}

export async function PATCH(request: NextRequest) {
  const { searchParams } = new URL(request.url);
  const id = searchParams.get('id');
  const body = await request.json();

  if (!id) {
    return NextResponse.json({ error: 'ID requerido' }, { status: 400 });
  }

  const { data, error } = await supabase
    .from('kiosk_configs')
    .update(body)
    .eq('id', id);

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  return NextResponse.json(data);
}