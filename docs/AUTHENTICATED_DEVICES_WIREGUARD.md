# Autenticación por cuenta y VPN WireGuard

## Migración

Ejecutar `016_authenticated_devices_wireguard.sql` y después `017_remove_legacy_pairing.sql`, en ese orden, tras las migraciones 001–015.
La migración conserva `owner_id` porque las rutas existentes de Control consultan ese campo.
El registro autenticado mantiene `owner_id` y `user_id` sincronizados y asigna una IP estable
de `10.88.0.0/16`. Las claves privadas nunca se envían ni se guardan en Supabase.

## Endpoint de registro

`POST /api/agent/register-auth` requiere `Authorization: Bearer <Supabase access_token>`.
Payload: `device_id`, `device_name`, `device_role=agent` y `public_key`.
La respuesta entrega un `agent_token` para que el agente continúe usando el protocolo de tareas
actual, más la IP persistente.

## Gateway WireGuard (paso de infraestructura obligatorio)

Configurar en el entorno de producción del backend:

- `WG_ENDPOINT`: hostname/IP público y puerto UDP del gateway.
- `WG_SERVER_PUBLIC_KEY`: clave pública del gateway.
- `WG_MANAGER_URL`: URL privada del servicio de administración del gateway.
- `WG_MANAGER_TOKEN`: secreto compartido para autenticar la administración.

El backend envía `POST /api/peers` al manager con `device_id`, `owner_id`, `public_key` y
`allowed_ip`. El manager debe aplicar el peer en WireGuard, persistir el estado y responder
2xx sólo después de aplicar la configuración. Este manager no se incluye ni se despliega
automáticamente en Vercel; debe ejecutarse junto al gateway Linux. Sin estas variables, el
registro y la IP persistente funcionan, pero la VPN permanece `not_configured` y no se
debe mostrar como conectada.

El gateway debe tener UDP público, forwarding IPv4 habilitado y la cadena `KIOSKGRID_WG` que
permite tráfico entre peers de una misma cuenta y bloquea tráfico entre cuentas. WireGuard es gratuito,
pero el servidor/gateway puede tener coste de infraestructura.

## Retirada de vinculación manual

La migración 017 elimina `pairing_code`, `pairing_expires_at`, `paired_at`, la tabla de
intentos y la función de rate-limit. Las rutas `/api/devices/pair` y `/api/agent/register`
y el generador local de códigos se retiran. Instala las nuevas APK en todos los agentes:
las versiones anteriores ya no podrán registrarse ni enviar heartbeats.

## Validación obligatoria

1. Aplicar migración en staging.
2. Registrar un dispositivo dos veces y confirmar que conserva la IP.
3. Intentar registrar el mismo hardware desde otra cuenta: debe responder 409.
4. Confirmar que la clave privada no aparece en requests ni en DB.
5. Provisionar peers de dos cuentas distintas y comprobar que el firewall bloquea tráfico cruzado.
6. Verificar el endpoint TCP `/health` por la IP VPN desde Control y probar handshake desde dos redes distintas.
