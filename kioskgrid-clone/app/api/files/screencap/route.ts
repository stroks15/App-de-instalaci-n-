import { NextRequest, NextResponse } from 'next/server';
import { supabase } from '@/lib/supabase';

export async function POST(request: NextRequest) {
  const { deviceId, type, fileName, fileContent } = await request.json();

  if (!deviceId || !type) {
    return NextResponse.json({ error: 'deviceId y type son requeridos' }, { status: 400 });
  }

  // Para screencap: guardamos la captura en tasks o en una tabla screencaps
  // Simplificamos guardando en una tabla screencaps que aún no existe, así que usamos tasks con tipo 'screencap'
  const { data, error } = await supabase
    .from('tasks')
    .insert([{
      device_id: deviceId,
      type: `screencap:${fileName}`,
      payload: { image: fileContent },
      status: 'queued'
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

  let query = supabase.from('tasks').select('*');
  if (deviceId) query = query.eq('device_id', deviceId);
  if (type) query = query.eq('type', type);

  const { data, error } = await query.order('created_at', { ascending: false });

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  return NextResponse.json(data);
}