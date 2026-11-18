# Autenticación por cuenta y VPN WireGuard

## Migración

Ejecutar `016_authenticated_devices_wireguard.sql` después de las migraciones 001–015.
La migración conserva `owner_id` porque las rutas existentes de Control consultan ese campo.
El registro autenticado mantiene `owner_id` y `user_id` sincronizados y asigna una IP estable
de `10.88.0.0/16`. Las claves privadas nunca se envían ni se guardan en Supabase.

## Endpoint de registro

`POST /api/agent/register-auth` requiere `Authorization: Bearer <Supabase access_token>`.
Payload: `device_id`, `device_name`, `device_role` y opcionalmente `public_key`.
La respuesta entrega un `agent_token` para que el agente continúe usando el protocolo de tareas
actual, más la IP persistente.

## Gateway WireGuard (paso de infraestructura obligatorio)

Configurar en el entorno de producción del backend:

- `WG_ENDPOINT`: hostname/IP público y puerto UDP del gateway.
- `WG_SERVER_PUBLIC_KEY`: clave pública del gateway.
- `WG_MANAGER_URL`: URL privada del servicio de administración del gateway.
- `WG_MANAGER_TOKEN`: secreto compartido para autenticar la administración.

El backend envía `POST /api/peers` al manager con `device_id`, `public_key` y
`allowed_ip`. El manager debe aplicar el peer en WireGuard, persistir el estado y responder
2xx sólo después de aplicar la configuración. Este manager no se incluye ni se despliega
automáticamente en Vercel; debe ejecutarse junto al gateway Linux. Sin estas variables, el
registro y la IP persistente funcionan, pero la VPN permanece `not_configured` y no se
debe mostrar como conectada.

El gateway debe tener UDP público, forwarding IPv4 habilitado, reglas firewall/NAT apropiadas,
rutas para `10.88.0.0/16` y aislamiento de peers por propietario. WireGuard es gratuito,
pero el servidor/gateway puede tener coste de infraestructura.

## Compatibilidad

Los clientes antiguos siguen usando el endpoint de pairing mientras se realiza la migración.
Después de desplegar las APK con login, se puede retirar el flujo antiguo en un cambio separado,
cuando no queden agentes antiguos activos. No borrar las columnas de pairing en esta migración.

## Validación obligatoria

1. Aplicar migración en staging.
2. Registrar un dispositivo dos veces y confirmar que conserva la IP.
3. Intentar registrar el mismo hardware desde otra cuenta: debe responder 409.
4. Confirmar que la clave privada no aparece en requests ni en DB.
5. Provisionar un peer de prueba en el gateway y verificar handshake desde dos redes distintas.
