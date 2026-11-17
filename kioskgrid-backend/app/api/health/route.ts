import { NextResponse } from 'next/server';
import { getSupabase } from '@/lib/supabase';

export const dynamic = 'force-dynamic';

/**
 * GET /api/health
 * Verifica el estado del backend y la conexión con Supabase.
 */
export async function GET() {
  const startTime = Date.now();

  let dbStatus = 'ok';
  let dbError: string | null = null;

  try {
    const supabase = getSupabase();
    const { error } = await supabase.from('tasks').select('id', { count: 'exact', head: true });
    if (error) {
      dbStatus = 'degraded';
      dbError = error.message;
    }
  } catch (e: any) {
    dbStatus = 'error';
    dbError = e.message;
  }

  const uptime = process.uptime();

  return NextResponse.json({
    status: dbStatus === 'ok' ? 'healthy' : 'degraded',
    timestamp: new Date().toISOString(),
    uptime_seconds: Math.floor(uptime),
    database: {
      status: dbStatus,
      error: dbError,
    },
    response_time_ms: Date.now() - startTime,
    version: '1.0.0',
    services: {
      supabase: dbStatus,
      storage: 'available',
    },
  });
}