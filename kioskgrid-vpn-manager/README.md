# KioskGrid WireGuard manager

This is the Linux-side peer control plane called by the Next.js API. It is intentionally
separate from Vercel: Vercel cannot configure the kernel WireGuard interface.

## Requirements

- Linux host with a public IPv4 address or reachable UDP forwarding.
- WireGuard tools (`wg`, `wg-quick`) and a configured `wg0` interface.
- Python 3.10+ (standard library only).
- HTTPS reverse proxy (example Caddyfile below).
- UDP 51820 exposed to the Internet; TCP 443 exposed for the manager API.

## Gateway interface

Example `/etc/wireguard/wg0.conf` (generate your own private key; never commit it):

```ini
[Interface]
Address = 10.88.0.1/16
ListenPort = 51820
PrivateKey = REPLACE_WITH_GATEWAY_PRIVATE_KEY
SaveConfig = true
PostUp = sysctl -w net.ipv4.ip_forward=1
```

Enable forwarding persistently with `net.ipv4.ip_forward=1` in sysctl. The gateway must
allow UDP 51820 and the OS must forward traffic between wg0 peers. Do not add NAT between
WireGuard peers; peer-to-peer packets should retain their 10.88.0.0/16 source addresses.

## Install manager

Copy this directory to `/opt/kioskgrid-vpn-manager`, then create
`/etc/kioskgrid-vpn-manager.env`:

```ini
WG_MANAGER_TOKEN=GENERATE_A_LONG_RANDOM_SECRET
WG_INTERFACE=wg0
WG_MANAGER_BIND=127.0.0.1
WG_MANAGER_PORT=8787
WG_STATE_FILE=/var/lib/kioskgrid-vpn-manager/peers.json
```

Protect the file: `chmod 600 /etc/kioskgrid-vpn-manager.env`.

Install the systemd unit from `systemd/kioskgrid-vpn-manager.service`, then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now wg-quick@wg0
sudo systemctl enable --now kioskgrid-vpn-manager
sudo systemctl status kioskgrid-vpn-manager
```

Configure Caddy with `Caddyfile.example` and point the public DNS name to the gateway.
The manager binds to loopback; never expose port 8787 directly.

## Vercel environment

Set these variables in the KioskGrid backend project:

- `WG_ENDPOINT=your-public-hostname:51820`
- `WG_SERVER_PUBLIC_KEY=the-wg0-interface-public-key`
- `WG_MANAGER_URL=https://vpn-manager.example.com`
- `WG_MANAGER_TOKEN=the-same-random-secret-as-the-manager-env-file`

The API calls `POST /api/peers` with the device public key, authenticated Supabase `owner_id`, and a single /32 address.
The manager validates that address is inside 10.88.0.0/16, applies `wg set`, rebuilds the
`KIOSKGRID_WG` firewall chain so peers can forward only to devices with the same owner ID,
and persists the peer using `wg-quick save`. It returns success only after those operations complete.

## Security notes

- Use a unique random token of at least 32 bytes; do not reuse Supabase credentials.
- Restrict manager access to HTTPS and the shared bearer token.
- Keep `wg0.conf`, private keys, state JSON and env files out of Git.
- This simple manager is a single-gateway design, not a distributed HA control plane.
- Add an owner-to-peer authorization layer before enabling peer-to-peer traffic between
  different KioskGrid accounts. The current API must only provision peers belonging to
  the authenticated owner; firewall policy should default-deny cross-account routes.
- For production multi-tenant isolation, use per-account routing/firewall sets or separate
  network namespaces/gateways. A single flat /16 alone is not an isolation boundary.
