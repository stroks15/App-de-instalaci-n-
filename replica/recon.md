# Recon Map: KioskGrid

## 1. Scope
- **App:** KioskGrid
- **Platform:** Web (Console) & Android (Agent)
- **Slice:** Core fleet management (Activar, Kiosco, Acciones masivas, Files, Info, Billing).
- **Audience:** Propietarios de flotas de dispositivos Android comerciales/kioscos.

## 2. Screen Inventory
| ID | Screen | Purpose |
| --- | --- | --- |
| S01 | Consola principal | Gestión de flota, selección y visibilidad de dispositivos. |
| S02 | Emparejar dispositivo | Activar nuevo dispositivo Android. |
| S03 | Info | Estado de Android y datos del dispositivo. |
| S04 | Control | Operaciones remotas (APK, reiniciar, reset). |
| S05 | Packages | Gestión de aplicaciones instaladas. |
| S06 | Files | Explorador de archivos remoto. |
| S07 | Screencap | Captura de pantalla en tiempo real. |
| S08 | Kiosk | Configuración de modo Kiosco (Web/Video). |

## 3. Key Flows
- **F01: Activación:** Instalación de agente + Emparejamiento.
- **F02: Configuración Kiosco:** Selección de contenido (Web/Video) + Aplicar a dispositivo/s.
- **F03: Acciones Masivas:** Selección multi-dispositivo + Encolado de comandos (offline support).

## 4. Inferred Data Model
- **Devices:** ID, Name, Android version, Status (Online/Offline).
- **Tasks/Commands:** ID, DeviceID, Type, Payload, Status (Queued/InProgress/Done).
- **KioskConfig:** ID, DeviceID, URL/Content, Orientation, IsHomeApp.
- **Subscription:** ID, Plan (Monthly/Annual), DeviceCount, TrialStatus.

## 5. Pricing & Billing
- **Trial:** 3 días para 2 dispositivos.
- **Monthly:** €8.12/dispositivo/mes.
- **Annual:** €81.20/dispositivo/año.
