import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';

export const dynamic = 'force-dynamic';

async function resolveDeviceIds(body: any): Promise<string[]> {
  const supabase = getSupabaseAdmin();
  if (body.all === true) {
    const { data } = await supabase.from('devices').select('device_id');
    return (data || []).map((d: any) => d.device_id);
  }
  if (Array.isArray(body.deviceIds) && body.deviceIds.length > 0) {
    return body.deviceIds;
  }
  if (body.deviceId) {
    return [body.deviceId];
  }
  return [];
}

/**
 * POST /api/apps/block
 * Bloquea o desbloquea una aplicación en uno o varios equipos (sin desinstalarla).
 * Body: { package, block: true|false, deviceId? | deviceIds? | all? }
 *
 * Nota: el bloqueo real (ocultar app) requiere que Dexterh4ck Equipos sea
 * Device Owner del equipo (ver README). Si no lo es, el agente reporta el requisito.
 */
export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { package: pkg, block = true } = body;

    if (!pkg) {
      return NextResponse.json({ error: 'package es requerido' }, { status: 400 });
    }

    const deviceIds = await resolveDeviceIds(body);
    if (deviceIds.length === 0) {
      return NextResponse.json({ error: 'No hay dispositivos destino' }, { status: 400 });
    }

    const supabase = getSupabaseAdmin();
    const now = new Date().toISOString();
    const type = block ? 'block_app' : 'unblock_app';

    const rows = deviceIds.map((deviceId: string) => ({
      device_id: deviceId,
      type,
      payload: pkg,
      status: 'queued',
      created_at: now,
      updated_at: now,
    }));

    const { error: taskError } = await (supabase.from('tasks') as any).insert(rows);
    if (taskError) throw taskError;

    return NextResponse.json({
      package: pkg,
      blocked: block,
      devices: deviceIds.length,
      deviceIds,
    }, { status: 201 });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}