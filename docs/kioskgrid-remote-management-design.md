# KioskGrid — diseño de las dos aplicaciones y control remoto

## Referencias visuales

Las dos capturas compartidas definen experiencias distintas. No mezclar sus estilos.

### 1. Dexterh4ck Equipos — Android TV, Fire TV Stick y Android Box

Usar la primera captura (tema oscuro) como referencia:
- Fondo azul marino/grafito, tarjetas discretas, bordes finos y acentos violeta/azul.
- Navegación lateral apta para mando y foco D-pad: Vincular equipo, Información, Almacenamiento, Memoria RAM y Ajustes.
- Vinculación con código corto de cinco caracteres, expiración visible y nombre editable.
- Tarjetas de hardware, conexión y estado ADB.
- El botón ADB abre ajustes del sistema; no activa depuración de forma oculta.
- Tipografía grande, contraste alto, objetivos amplios y orden de foco predecible para TV.

### 2. Dexterh4ck Control — aplicación móvil

Usar la segunda captura (tema claro, panel azul) como referencia:
- Navegación azul lateral en tablet/escritorio y navegación inferior o menú adaptable en móvil.
- Inicio con Instalador, Gestionar Apps, Repositorios y Ajustes.
- Lista de equipos vinculados con estado online/offline, última conexión y modelo.
- Detalle con apps instaladas, almacenamiento, RAM, seguridad y actualizaciones.
- Acciones de instalación, actualización, desinstalación, apertura y organización de apps.
- En móvil, navegación compacta; en pantallas grandes, conservar el panel lateral.

## Funciones nuevas solicitadas

### A. Pantalla remota en vivo

Agregar en Control una sección Pantalla remota por dispositivo:
- Iniciar/detener transmisión en vivo.
- Mostrar estado, calidad, latencia y reconexión.
- Ver la pantalla del TV/Box en tiempo real.
- Controles táctiles sobre la vista: toque, mantener, desplazamiento y navegación Atrás/Inicio/Recientes.
- La sesión debe ser visible en el equipo y terminar al revocar permiso o cerrar la sesión.

Implementación Android prevista:
- Captura mediante MediaProjection con consentimiento explícito en el dispositivo y servicio foreground/notificación mientras se comparte.
- Transporte de baja latencia mediante WebRTC; backend sólo intercambia señalización y credenciales efímeras, no retransmite vídeo.
- Para interacción, usar AccessibilityService activado expresamente por el usuario, con indicador visible y acciones limitadas a la sesión autorizada. Sin ese permiso, ofrecer sólo visualización.
- No eludir la ventana de consentimiento, permisos Android ni restricciones Fire OS.

### B. Organizador remoto del launcher

Agregar en Control una vista del launcher con aplicaciones y posiciones:
- Reordenar iconos mediante arrastrar y soltar.
- Fijar/quitar favoritos y crear grupos cuando el launcher lo soporte.
- Previsualizar cambios y mostrar resultado/error.
- Guardar el orden por dispositivo y copiar una distribución a otros equipos.

El agente sólo puede cambiar el orden de forma fiable si el launcher ofrece API/intención compatible o si el agente proporciona el launcher administrado. Launchers de terceros sin API deben marcarse como incompatibles. Device Owner no concede una API genérica para reordenar cualquier launcher.

### C. Consola de comandos ADB remotos

Agregar en Control una consola por equipo:
- Mostrar disponibilidad ADB y estado de emparejamiento.
- Emparejar mediante Android Wireless Debugging/ADB Pairing o conexión previamente autorizada.
- Permitir comandos de diagnóstico predefinidos y consola avanzada sólo a administradores autorizados.
- Mostrar salida, código de retorno, duración, hora, operador y registro auditable.
- Bloquear comandos destructivos/de alto riesgo por defecto; pedir confirmación para reinicio, borrado, instalación y cambios de configuración.
- No guardar claves privadas ADB en el móvil ni en logs; credenciales/sesiones con caducidad y revocación.

ADB por red no está disponible universalmente en Fire OS/Android TV y no se puede habilitar silenciosamente desde una app normal. El usuario debe activar y autorizar la depuración/emparejamiento en el equipo. No exponer un puerto ADB público a Internet. Preferir comandos tipados del agente sobre shell arbitraria.

## Arquitectura y seguridad obligatorias

1. Identidad: Control autentica al usuario con Supabase Auth. Toda acción se vincula al propietario del equipo.
2. Autorización: backend valida JWT, propiedad del dispositivo y permisos antes de crear, consultar o actualizar tareas. El agente autentica heartbeat y consulta de tareas con credencial de dispositivo rotatoria.
3. Tareas: tipos estructurados y payload JSON validado; no concatenar comandos shell desde texto libre. Añadir expiración, nonce/idempotencia, estado, resultado y auditoría.
4. Realtime: canal privado por dispositivo para señalización WebRTC y notificaciones; nunca canal público ni clave service-role dentro de APKs.
5. Transporte: HTTPS/WSS y DTLS-SRTP para WebRTC. Tokens efímeros, permisos por sesión y revocación.
6. Privacidad: indicador persistente en TV durante captura/control; registrar inicio/fin y operador; no capturar en segundo plano sin consentimiento.
7. Compatibilidad: detectar versión Android/Fire OS y capacidades MediaProjection, Accessibility, Device Owner, launcher y ADB. Deshabilitar controles no soportados con explicación.

## API y datos propuestos

- POST /api/devices/:id/remote-sessions — solicitar/iniciar sesión tras comprobar propietario y capacidades.
- POST /api/devices/:id/remote-sessions/:sessionId/stop — revocar sesión.
- POST /api/devices/:id/remote-sessions/:sessionId/signal — señalización WebRTC autenticada.
- POST /api/devices/:id/tasks — crear tarea tipada y autorizada.
- GET /api/devices/:id/launcher-layout y PUT /api/devices/:id/launcher-layout — leer/guardar layout compatible.
- Tabla remote_sessions: id, device_id, owner_id, status, capabilities, started_at, expires_at, ended_at.
- Tabla device_audit_logs: owner_id, device_id, actor_id, action, metadata redactada, created_at.
- Tabla launcher_layouts: device_id, owner_id, launcher_package, layout JSON, updated_at.

Las tablas/rutas son propuesta de implementación; deben añadirse mediante migraciones revisadas y políticas RLS específicas.

## Bloqueadores detectados en el código actual

- GET/POST/PATCH /api/tasks y GET/PATCH/DELETE /api/tasks/:taskId usan cliente admin y no verifican en la ruta identidad/propiedad del usuario o agente. Deben cerrarse antes de exponer nuevas operaciones remotas.
- GET /api/tasks permite consultar tareas sin exigir deviceId ni demostrar identidad de agente.
- El endpoint /api/files/screencap usa cliente público y tiene una cadena de consulta/insert inconsistente; no debe reutilizarse como canal de vídeo.
- CommandService hace polling y ejecuta tareas existentes. No implementa MediaProjection, WebRTC, interacción por Accessibility ni ADB remoto.
- Instalación silenciosa sigue restringida a equipos legítimamente provisionados como Device Owner; en los demás se usa el flujo de instalación del sistema.

## Criterios de aceptación

- Las dos apps conservan las identidades visuales descritas y se adaptan a TV, teléfono y tablet.
- Un usuario sólo ve/controla dispositivos que ha vinculado y posee.
- Pantalla remota requiere consentimiento, muestra indicador visible y se revoca desde ambos extremos.
- Interacción remota sólo funciona con permiso de accesibilidad concedido; sin él, transmisión sólo lectura.
- Reordenar apps sólo se ofrece en launchers compatibles y el cambio se confirma en el dispositivo.
- ADB sólo funciona tras emparejamiento/autorización explícitos, con comandos auditados y sin puertos públicos.
- CI compila ambas APK y prueba backend; instalación y sesiones reales se validan en hardware antes de declarar la función lista.

## Estado

Este documento define el alcance y las salvaguardas de la siguiente fase. No afirma que la transmisión en vivo, interacción remota, organizador universal del launcher o ADB remoto estén implementados todavía.
