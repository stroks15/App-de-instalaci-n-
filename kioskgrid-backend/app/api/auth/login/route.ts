import { NextRequest, NextResponse } from 'next/server';
import { createClient } from '@supabase/supabase-js';

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest): Promise<Response> {
  try {
    const body = await request.json();
    const email = typeof body.email === 'string' ? body.email.trim() : '';
    const password = typeof body.password === 'string' ? body.password : '';
    if (!email || !password || email.length > 320 || password.length > 1024) {
      return NextResponse.json({ error: 'Correo o contraseña inválidos.' }, { status: 400 });
    }

    const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
    const anonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;
    if (!url || !anonKey) {
      return NextResponse.json({ error: 'Autenticación no configurada.' }, { status: 503 });
    }

    const supabase = createClient(url, anonKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data, error } = await supabase.auth.signInWithPassword({ email, password });
    if (error || !data.session || !data.user) {
      return NextResponse.json({ error: 'Correo o contraseña incorrectos.' }, { status: 401 });
    }

    return NextResponse.json({
      access_token: data.session.access_token,
      user_id: data.user.id,
      expires_at: data.session.expires_at,
    }, {
      headers: { 'Cache-Control': 'no-store' },
    });
  } catch (error) {
    console.error('POST /api/auth/login failed:', error);
    return NextResponse.json({ error: 'No se pudo iniciar sesión.' }, { status: 500 });
  }
}
