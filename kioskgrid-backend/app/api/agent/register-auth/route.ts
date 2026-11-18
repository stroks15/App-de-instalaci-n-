import { randomBytes } from 'crypto';
import { NextRequest, NextResponse } from 'next/server';
import { getSupabaseAdmin } from '@/lib/supabase';
import { requireUser } from '@/lib/request-auth';
import { hashAgentToken } from '@/lib/agent-auth';

export const dynamic = 'force-dynamic';

type GatewayResult = {
  enabled: boolean;
  status: 'active' | 'pending_gateway' | 'not_configured' | 'error';
  endpoint?: string;
  server_public_key?: string;
};

async function provisionGatewayPeer(device: {
  device_id: string;
  public_key: string | null;
  assigned_ip: string;
}, ownerId: string): Promise<GatewayResult> {
  const managerUrl = process.env.WG_MANAGER_URL;
  const managerToken = process.env.WG_MANAGER_TOKEN;
  const endpoint = process.env.WG_ENDPOINT;
  const serverPublicKey = process.env.WG_SERVER_PUBLIC_KEY;

  if (!managerUrl || !managerToken || !endpoint || !serverPublicKey || !device.public_key) {
    return { enabled: false, status: 'not_configured' };
  }

  try {
    const response = await fetch(new URL('/api/peers', managerUrl), {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        authorization: `Bearer ${managerToken}`,
      },
      body: JSON.stringify({
        device_id: device.device_id,
        owner_id: ownerId,
        public_key: device.public_key,
        allowed_ip: `${device.assigned_ip.split('/')[0]}/32`,
      }),
      signal: AbortSignal.timeout(8000),
      cache: 'no-store',
    });
    if (!response.ok) {
      console.error('WireGuard manager rejected peer:', response.status);
      return { enabled: false, status: 'error' };
    }
    return {
      enabled: true,
      status: 'active',
      endpoint,
      server_public_key: serverPublicKey,
    };
  } catch (error) {
    console.error('WireGuard peer provisioning failed:', error);
    return { enabled: false, status: 'pending_gateway' };
  }
}

export async function POST(request: NextRequest): Promise<Response> {
  const auth = await requireUser(request);
  if (auth.response || !auth.user) return auth.response;

  try {
    const body = await request.json();
    const deviceId = String(body.device_id || '').trim();
    const deviceName = String(body.device_name || '').trim();
    const role = String(body.device_role || 'agent');
    const publicKey = typeof body.public_key === 'string' ? body.public_key.trim() : null;

    if (!deviceId || !deviceName || deviceName.length > 80 ||
        role !== 'agent' ||
        (publicKey !== null && !/^[A-Za-z0-9+/]{43}=$/.test(publicKey))) {
      return NextResponse.json({ error: 'Datos de dispositivo inválidos' }, { status: 400 });
    }

    const db = getSupabaseAdmin();
    const { data, error } = await db.rpc('register_authenticated_device', {
      p_user_id: auth.user.id,
      p_device_id: deviceId,
      p_device_name: deviceName,
      p_device_role: role,
      p_public_key: publicKey,
    });
    if (error) {
      if (error.message?.includes('device_already_owned')) {
        return NextResponse.json({ error: 'Este dispositivo pertenece a otra cuenta.' }, { status: 409 });
      }
      console.error('Authenticated device registration failed:', error);
      return NextResponse.json({ error: 'No se pudo registrar el dispositivo' }, { status: 500 });
    }

    const device = Array.isArray(data) ? data[0] : data;
    if (!device) throw new Error('Device registration returned no row');

    // Rotate the agent credential only after ownership has been verified.
    const agentToken = randomBytes(32).toString('base64url');
    const { error: tokenError } = await db.from('devices')
      .update({ agent_key_hash: hashAgentToken(agentToken) })
      .eq('id', device.id)
      .eq('owner_id', auth.user.id);
    if (tokenError) throw tokenError;

    const vpn = await provisionGatewayPeer({
      device_id: device.device_id,
      public_key: device.public_key,
      assigned_ip: device.assigned_ip,
    }, auth.user.id);

    await db.from('devices').update({
      vpn_enabled: vpn.enabled,
      vpn_status: vpn.status,
    }).eq('id', device.id).eq('owner_id', auth.user.id);

    return NextResponse.json({
      device,
      agent_token: agentToken,
      vpn: {
        ...vpn,
        assigned_ip: device.assigned_ip,
        allowed_ips: '10.88.0.0/16',
        persistent_keepalive: 25,
      },
    }, { status: 200 });
  } catch (error) {
    console.error('POST /api/agent/register-auth', error);
    return NextResponse.json({ error: 'No se pudo completar el registro autenticado' }, { status: 500 });
  }
}
