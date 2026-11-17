import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';

export const dynamic = 'force-dynamic';

/**
 * GET /api/tasks?deviceId=XXX&status=queued&type=install
 * Lista tareas con filtros. El agente hace polling con deviceId + status=queued.
 */
export async function GET(request: NextRequest) {
  try {
    const { searchParams } = new URL(request.url);
    const deviceId = searchParams.get('deviceId');
    const status = searchParams.get('status');
    const type = searchParams.get('type');
    const limit = parseInt(searchParams.get('limit') || '50');

    const supabase = getSupabaseAdmin();
    let query = supabase.from('tasks').select('*');

    if (deviceId) {
      query = query.eq('device_id', deviceId);
    }
    if (status) {
      query = query.eq('status', status);
    }
    if (type) {
      query = query.eq('type', type);
    }

    const { data, error } = await query
      .order('created_at', { ascending: false })
      .limit(limit);

    if (error) throw error;
    return NextResponse.json(data || []);
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

/**
 * POST /api/tasks
 * Crea una nueva tarea (desde el controlador) o reporta resultado (desde el agente).
 * Si el body incluye "id", se actualiza en lugar de crear.
 */
export async function POST(request: NextRequest) {
  try {
    const body = await request.json();

    // Si trae id + status, tratar como actualización
    if (body.id && (body.status || body.result || body.error)) {
      return updateTask(body.id, body);
    }

    // Validación para crear tarea
    if (!body.device_id || !body.type) {
      return NextResponse.json(
        { error: 'device_id y type son requeridos' },
        { status: 400 }
      );
    }

    const supabase = getSupabaseAdmin();
    const task = {
      device_id: body.device_id,
      type: body.type,
      payload: body.payload || null,
      status: body.status || 'queued',
      result: body.result || null,
      error: body.error || null,
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    };

    const { data, error } = await (supabase.from('tasks') as any).insert([task]).select().single();
    if (error) throw error;

    return NextResponse.json(data, { status: 201 });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

/**
 * PATCH /api/tasks
 * Actualiza estado/resultado de una tarea existente.
 * Body: { id, status, result?, error? }
 */
export async function PATCH(request: NextRequest) {
  try {
    const body = await request.json();
    if (!body.id) {
      return NextResponse.json({ error: 'id es requerido' }, { status: 400 });
    }
    return await updateTask(body.id, body);
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

// Helper interno para actualizar tarea
async function updateTask(id: string, body: any) {
  const allowed = ['status', 'result', 'error'];
  const updates: Record<string, any> = { updated_at: new Date().toISOString() };
  for (const key of allowed) {
    if (key in body) updates[key] = body[key];
  }

  const supabase = getSupabaseAdmin();
  const { data, error } = await (supabase.from('tasks') as any)
    .update(updates)
    .eq('id', id)
    .select()
    .single();

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
  return NextResponse.json(data);
}