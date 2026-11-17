import { NextRequest, NextResponse } from 'next/server';
import { getSupabase } from '@/lib/supabase';

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest) {
  const body = await request.json();
  const { deviceId, type, fileName, fileContent } = body;

  if (!deviceId || !type) {
    return NextResponse.json({ error: 'deviceId y type son requeridos' }, { status: 400 });
  }

  const supabase = await getSupabase();
  const { data, error } = await (supabase.from('tasks') as any)
    .from('tasks')
    .insert([{
      device_id: deviceId,
      type: `screencap:${encodeURIComponent(fileName)}`,
      payload: { image: fileContent },
      status: 'queued',
    }]);

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  return NextResponse.json(data);
}

export async function GET(request: NextRequest) {
  const { searchParams } = new URL(request.url);
  const deviceId = searchParams.get('deviceId');
  const type = searchParams.get('type');

  const supabase = await getSupabase();

  let query = supabase.from('tasks').select('*');

  if (deviceId) {
    query = query.eq('device_id', deviceId);
  }
  if (type) {
    query = query.eq('type', type);
  }

  const { data, error } = await query.order('created_at', { ascending: false });

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  return NextResponse.json(data);
}
