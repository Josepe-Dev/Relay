# Llamadas, Videollamadas y WebRTC (`CallManager`)

Relay unifica bajo una sola interfaz multiplataforma (`CallManager`) tres modos de comunicación en tiempo real según la conectividad disponible: **Nube (Agora RTC)**, **LAN Offline (WebRTC directo)** y **Mesh Offline (Opus nativo)**.

---

## 1. Matriz de capacidades por transporte

| Transporte | Audio | Video HD | Pantalla compartida | Grupales | Requisito |
|---|:---:|:---:|:---:|:---:|---|
| **Cloud (Agora RTC)** | Sí | Sí (multicámara + zoom) | Sí | Sí | Internet |
| **LAN Offline (WebRTC)** | Sí | Sí (multicámara + zoom) | Sí | Sí | Mismo router Wi-Fi o Mobile Hotspot |
| **Mesh Nearby (Wi-Fi Direct)** | Sí (Opus) | No | No | No | Proximidad Android (1-a-1 directo) |
| **Mesh Nearby (BLE)** | PTT Walkie-Talkie | No | No | No | Proximidad Android |

> **Por qué no hay video sobre Nearby Connections:** Nearby Connections expone canales RPC de alto nivel (`BYTES`, `FILE`, `STREAM`), no sockets IP directos donde WebRTC pueda enlazar candidatos ICE UDP/RTP. Por ello, en Nearby las llamadas de voz 1-a-1 usan un pipeline nativo de captura + codificación **Opus (24–32 kbps)**, reservando WebRTC HD para redes IP locales (LAN / SoftAP).

---

## 2. WebRTC LAN sin internet ni servidores STUN/TURN

En una red Wi-Fi compartida o un Mobile Hotspot:
1. **Cero servidores externos:** Los clientes recolectan candidatos `typ host` locales (IPv4 privadas `192.168.x.x` / `10.x.x.x`) y negocian SDP directamente por los sockets TCP de Ktor.
2. **Soporte de Mobile Hotspot (SoftAP) en Android:**
   - El `NetworkMonitor` estándar de Android suele omitir la interfaz SoftAP (`ap0`, `swlan0`, `softap0`, `rndis0`) al recolectar candidatos ICE.
   - Relay registra un adaptador personalizado de `NetworkMonitor` antes de inicializar `PeerConnectionFactory` e inyecta candidatos `typ host` UDP sobre las IPv4 SoftAP activas, logrando videollamadas WebRTC directas cuando el teléfono anfitrión comparte su punto de acceso sin internet ni datos móviles.
3. **Orden determinístico de líneas `m=` en SDP:**
   - Para interoperabilidad pura sin SFU entre **Chromium (JCEF en Desktop)** y **WebRTC nativo en Android**, todas las ofertas y respuestas respetan el orden estricto: `mid=0` (Audio), `mid=1` (Cámara), `mid=2` (Pantalla compartida), evitando rechazos por desajuste de *m-lines* y colisiones *glare*.

---

## 3. Estabilidad, Cámara y Desktop JCEF

- **Teardown libre de deadlocks:** Al colgar en Android, los locks de estado se liberan en `< 1 ms` y el cierre JNI de `PeerConnection.close()` / `dispose()` y `AudioRecord`/`AudioTrack` se ejecuta fuera del hilo principal y de `signaling_thread` para prevenir ANRs.
- **Cámara multicámara y espejo selectivo:** Inspección de focales disponibles (ultra gran angular, principal, teleobjetivo) con presets y pinch-to-zoom continuo, reinicio automático a `1.0x` al alternar cámara frontal y espejo aplicado exclusivamente al stream frontal propio.
- **Desktop JVM (Windows / macOS / Linux):** El puente JCEF ejecuta la sesión RTC en origen local aislado (`127.0.0.1`) junto con un servidor de captura de pantalla con control de framerate para no saturar compositores X11/XWayland en Linux.
