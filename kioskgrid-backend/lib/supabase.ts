import { createClient } from '@supabase/supabase-js';

const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL || '';
const supabaseAnonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || '';
const supabaseServiceKey = process.env.SUPABASE_SERVICE_ROLE_KEY || '';

let supabaseClient: ReturnType<typeof createClient> | undefined;
let supabaseAdminClient: ReturnType<typeof createClient> | undefined;

/**
 * Cliente Supabase público (anon key). Lazy initialization.
 */
export const getSupabase = () => {
  if (!supabaseClient) {
    supabaseClient = createClient(supabaseUrl, supabaseAnonKey, {
      auth: {
        autoRefreshToken: true,
        persistSession: true,
        detectSessionInUrl: true,
      },
    });
  }
  return supabaseClient;
};

/**
 * Cliente Supabase con service role key (operaciones administrativas).
 * Lazy initialization para evitar errores en build time.
 */
export const getSupabaseAdmin = () => {
  if (!supabaseServiceKey) {
    throw new Error('SUPABASE_SERVICE_ROLE_KEY must be set');
  }
  if (!supabaseAdminClient) {
    supabaseAdminClient = createClient(supabaseUrl, supabaseServiceKey, {
      auth: {
        autoRefreshToken: false,
        persistSession: false,
      },
    });
  }
  return supabaseAdminClient;
};