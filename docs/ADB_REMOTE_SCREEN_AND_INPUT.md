# Pantalla remota e interacción mediante ADB

## Sí: ADB puede proporcionar pantalla y entrada

Para equipos Android TV / Android Box con depuración ADB habilitada y autorizada, la vía práctica es usar **scrcpy sobre ADB**:
- El cliente host usa ADB para autenticar y arrancar el servidor scrcpy temporal en el equipo.
- El servidor envía vídeo H.264/H.265 y un canal de control separado.
- El cliente decodifica el vídeo y reenvía toques, deslizamientos, teclas y navegación.
- La pantalla no necesita que Equipos tenga permisos de accesibilidad ni Device Owner para la inyección de entrada de scrcpy, pero sí necesita autorización ADB del usuario en el dispositivo.

Referencia técnica: https://github.com/Genymobile/scrcpy y https://scrcpyapp.org/en/guides/develop/

## Arquitectura que se integrará con Dexterh4ck Control

No se debe enviar ADB abierto a Internet ni ejecutar comandos shell arbitrarios recibidos desde la nube.

1. El usuario activa Opciones de desarrollador y Depuración inalámbrica / ADB en el equipo, y autoriza la clave del host.
2. Un **puente ADB de confianza** en la misma LAN (PC Windows/Linux/macOS o un host local dedicado) conserva la clave privada ADB y conecta al equipo.
3. El puente arranca scrcpy server por ADB, recibe vídeo y canal de control.
4. El puente establece una conexión saliente TLS/WebRTC hacia la sesión autenticada de KioskGrid; no abre puertos entrantes públicos.
5. Control solicita una sesión, muestra indicador y operador; el usuario del equipo acepta la sesión. La sesión expira y puede revocarse desde ambos extremos.
6. Backend sólo autentica/autoriza y señaliza; nunca guarda claves ADB ni retransmite el vídeo.

**Por qué hace falta el puente:** la app Android Control no puede invocar el binario ADB del sistema ni adquirir privilegios shell por ser Device Owner el equipo remoto. Device Owner no equivale a UID shell. Para conexión móvil directa habría que implementar un cliente ADB compatible y el protocolo scrcpy completo dentro de Control, conservar las claves de emparejamiento de forma segura y resolver conectividad LAN/NAT; no es una simple llamada HTTP.

## Prueba manual desde Windows

Instala Android Platform Tools y scrcpy oficial. Conecta USB o habilita ADB TCP/IP / Wireless Debugging y autoriza el host en el equipo.

```powershell
adb devices
# Con depuración inalámbrica, usa el IP:puerto que muestra el dispositivo:
adb pair 192.168.1.50:37123
# Introduce el código de emparejamiento mostrado en Android
adb connect 192.168.1.50:42567
adb devices
scrcpy --serial 192.168.1.50:42567 --max-size 1280 --video-bit-rate 4M --stay-awake
```

Con USB, ejecuta `scrcpy -d --max-size 1280 --video-bit-rate 4M --stay-awake`.

En scrcpy, el vídeo y la interacción se manejan en una sesión; usa el ratón para tocar/arrastrar y el teclado para enviar teclas. Cierra la ventana para terminar la sesión y desconectar el servidor temporal.

## Límites del comando screenrecord

`adb shell screenrecord` sirve para grabación/captura, no es la base adecuada para control interactivo en vivo:
- La grabación estándar tiene límite máximo de 180 segundos.
- No incluye canal de entrada táctil/teclas.
- La documentación Android indica que no graba audio y que cambios de orientación durante la grabación no están soportados.

Por eso la solución interactiva usa scrcpy, no un bucle de `screenrecord` + `input tap`.

## Seguridad y compatibilidad

- ADB sólo tras aceptación explícita de la huella/clave en el TV/Box.
- Nunca exponer 5555 ni otro puerto ADB directamente a Internet.
- Mantener claves privadas en el puente local, no en APK, backend, logs ni almacenamiento compartido.
- Permitir una sesión por equipo, tokens efímeros, vencimiento, auditoría y revocación.
- Mostrar indicador persistente de sesión y nombre del operador.
- El modo inalámbrico puede cambiar el puerto tras reiniciar; volver a emparejar/conectar según lo que muestre Android.
- Fire OS y firmwares de fabricantes pueden bloquear ADB inalámbrico o limitar comandos. Verificar por modelo.
- Esto todavía requiere construir el puente ADB y el cliente de visualización/control dentro de Control; el script/manual de prueba no significa que la función esté integrada en las APK.
