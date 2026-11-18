import { createHash } from 'crypto';
import type { NextRequest } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';

type SupabaseAdmin = ReturnType<typeof getSupabaseAdmin>;

export async function consumePairingAttempt(db: SupabaseAdmin, request: NextRequest, identifiers: string[]) {
  const forwarded = request.headers.get('x-forwarded-for');
  const ip = request.headers.get('x-real-ip') || forwarded?.split(',').at(-1)?.trim() || 'unknown';
  const scopes = [...new Set([`ip:${ip}`, ...identifiers])];
  let retryAfterSeconds = 0;
  for (const scope of scopes) {
    const scopeHash = createHash('sha256').update(scope).digest('hex');
    const { data, error } = await (db as any).rpc('consume_pairing_attempt', {
      p_scope_hash: scopeHash, p_max_attempts: 5, p_window_seconds: 900, p_block_seconds: 900
    });
    if (error || !Array.isArray(data) || typeof data[0]?.allowed !== 'boolean') return { allowed: false as const, unavailable: true as const };
    if (!data[0].allowed) retryAfterSeconds = Math.max(retryAfterSeconds, Number(data[0].retry_after_seconds) || 900);
  }
  return retryAfterSeconds > 0 ? { allowed: false as const, retryAfterSeconds } : { allowed: true as const };
}

export function pairingRateLimitResponse(retryAfterSeconds: number) {
  return new Response(JSON.stringify({ error: 'Demasiados intentos de vinculación. Inténtalo más tarde.' }), {
    status: 429, headers: { 'Content-Type': 'application/json', 'Retry-After': String(retryAfterSeconds) }
  });
}