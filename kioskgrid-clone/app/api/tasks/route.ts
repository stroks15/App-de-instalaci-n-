import { NextRequest, NextResponse } from 'next/server';
import { supabase } from '@/lib/supabase';

export async function GET(request: NextRequest) {
  const { searchParams } = new URL(request.url);
  const deviceId = searchParams.get('deviceId');
  const status = searchParams.get('status');

  let query = supabase.from('tasks').select('*');

  if (deviceId) {
    query = query.eq('device_id', deviceId);
  }
  if (status) {
    query = query.eq('status', status);
  }

  const { data, error } = await query.order('created_at', { ascending: false });

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  return NextResponse.json(data);
}

export async function POST(request: NextRequest) {
  const body = await request.json();
  const { deviceId, type, payload } = body;

  if (!deviceId || !type) {
    return NextResponse.json({ error: 'deviceId y type son requeridos' }, { status: 400 });
  }

  const { data, error } = await supabase
    .from('tasks')
    .insert([{ device_id: deviceId, type, payload }]);

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
    .from('tasks')
    .update(body)
    .eq('id', id);

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  return NextResponse.json(data);
}