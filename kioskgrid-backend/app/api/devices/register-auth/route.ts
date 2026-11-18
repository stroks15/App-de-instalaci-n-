import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { requireUser } from '@/lib/request-auth';

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest): Promise<Response> {
  const auth = await requireUser(request);
  if (auth.response || !auth.user) return auth.response;
  try {
    const body = await request.json();
    const deviceId = String(body.device_id || '').trim();
    const deviceName = String(body.device_name || '').trim();
    const publicKey = typeof body.public_key === 'string' ? body.public_key.trim() : null;
    if (!deviceId || !/^[A-Za-z0-9._:-]{8,128}$/.test(deviceId) ||
        !deviceName || deviceName.length > 80 ||
        (publicKey !== null && !/^[A-Za-z0-9+/]{43}=$/.test(publicKey))) {
      return NextResponse.json({ error: 'Datos de controlador inválidos' }, { status: 400 });
    }

    const db = getSupabaseAdmin();
    const { data, error } = await db.rpc('register_authenticated_device', {
      p_user_id: auth.user.id,
      p_device_id: deviceId,
      p_device_name: deviceName,
      p_device_role: 'control',
      p_public_key: publicKey,
    });
    if (error) {
      if (error.message?.includes('device_already_owned')) {
        return NextResponse.json({ error: 'Este controlador pertenece a otra cuenta.' }, { status: 409 });
      }
      console.error('Controller registration failed:', error);
      return NextResponse.json({ error: 'No se pudo registrar el controlador' }, { status: 500 });
    }
    const device = Array.isArray(data) ? data[0] : data;
    if (!device) throw new Error('Controller registration returned no row');

    const managerUrl = process.env.WG_MANAGER_URL;
    const managerToken = process.env.WG_MANAGER_TOKEN;
    const endpoint = process.env.WG_ENDPOINT;
    const serverPublicKey = process.env.WG_SERVER_PUBLIC_KEY;
    let vpn = { enabled: false, status: 'not_configured' as string };
    if (managerUrl && managerToken && endpoint && serverPublicKey && device.public_key) {
      try {
        const response = await fetch(new URL('/api/peers', managerUrl), {
          method: 'POST',
          headers: { 'content-type': 'application/json', authorization: `Bearer ${managerToken}` },
          body: JSON.stringify({
            device_id: device.device_id,
            public_key: device.public_key,
            allowed_ip: `${String(device.assigned_ip).split('/')[0]}/32`,
          }),
          signal: AbortSignal.timeout(8000),
          cache: 'no-store',
        });
        vpn = response.ok
          ? { enabled: true, status: 'active' }
          : { enabled: false, status: 'error' };
      } catch {
        vpn = { enabled: false, status: 'pending_gateway' };
      }
    }

    await db.from('devices').update({
      vpn_enabled: vpn.enabled,
      vpn_status: vpn.status,
    }).eq('id', device.id).eq('owner_id', auth.user.id);

    return NextResponse.json({
      device,
      vpn: {
        ...vpn,
        assigned_ip: device.assigned_ip,
        endpoint: vpn.enabled ? endpoint : null,
        server_public_key: vpn.enabled ? serverPublicKey : null,
        allowed_ips: '10.88.0.0/16',
        persistent_keepalive: 25,
      },
    }, { headers: { 'Cache-Control': 'no-store' } });
  } catch (error) {
    console.error('POST /api/devices/register-auth', error);
    return NextResponse.json({ error: 'No se pudo registrar el controlador' }, { status: 500 });
  }
}
