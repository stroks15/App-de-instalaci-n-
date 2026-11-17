import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';

export const dynamic = 'force-dynamic';

/**
 * GET /api/tasks/[taskId]
 * Obtiene el detalle de una tarea específica (útil para rastrear progreso).
 */
export async function GET(
  request: NextRequest,
  { params }: { params: { taskId: string } }
) {
  try {
    const taskId = params.taskId;
    const supabase = getSupabaseAdmin();

    const { data, error } = await supabase
      .from('tasks')
      .select('*')
      .eq('id', taskId)
      .single();

    if (error || !data) {
      return NextResponse.json({ error: 'Tarea no encontrada' }, { status: 404 });
    }

    const taskData = data as any;
    let result: any = null;
    if (taskData?.result) {
      try {
        result = JSON.parse(taskData.result);
      } catch {
        result = taskData.result;
      }
    }

    return NextResponse.json({ task: { ...taskData, result } });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

/**
 * PATCH /api/tasks/[taskId]
 * Actualiza el estado y resultado de una tarea.
 * Usado por el agente para reportar progreso (in_progress, completed, failed).
 */
export async function PATCH(
  request: NextRequest,
  { params }: { params: { taskId: string } }
) {
  try {
    const taskId = params.taskId;
    const body = await request.json();

    const allowedUpdates = ['status', 'result', 'error'];
    const updates: Record<string, any> = { updated_at: new Date().toISOString() };
    for (const key of allowedUpdates) {
      if (key in body) updates[key] = body[key];
    }

    const supabase = getSupabaseAdmin();
    const { data, error } = await (supabase.from('tasks') as any)
      .update(updates)
      .eq('id', taskId)
      .select()
      .single();

    if (error) throw error;
    return NextResponse.json({ task: data });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

/**
 * DELETE /api/tasks/[taskId]
 * Elimina una tarea específica.
 */
export async function DELETE(
  request: NextRequest,
  { params }: { params: { taskId: string } }
) {
  try {
    const taskId = params.taskId;
    const supabase = getSupabaseAdmin();

    const { error } = await (supabase.from('tasks') as any).delete().eq('id', taskId);
    if (error) throw error;

    return NextResponse.json({ message: 'Tarea eliminada correctamente' });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}