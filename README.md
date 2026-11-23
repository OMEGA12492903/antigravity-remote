# Antigravity Remote (Android)

App nativa para Android que envuelve **Google Antigravity Remote Control** (`https://antigravity.google.com`) usando **Mozilla GeckoView** (motor completo de Firefox) para mantener el **100 % de las funciones del navegador** (login de Google + Passkeys, artefactos, diffs, aprobaciones, carga de archivos, notificaciones) con una experiencia mucho más cómoda en móvil.

## Qué mejora frente al navegador
- **Pantalla completa real** sin barra de direcciones ni pestañas (con soporte edge-to-edge y ajuste limpio del teclado virtual).
- **Inicio de sesión de Google nativo** (GeckoView no sufre el bloqueo `disallowed_useragent` de Android WebView y soporta Passkeys/WebAuthn).
- **Botón flotante arrastrable** (o **barra inferior fija** opcional) con acceso en 1 toque a:
  - Atrás / Recargar / Inicio / Ir al final del chat / Enfocar caja de texto.
  - **Modo Escritorio** en 1 toque para ver diffs anchos o la barra lateral completa.
  - **Zoom de texto y código** (`85%` – `120%`) sin recargar la sesión.
  - **Mantener sesión viva en 2º plano** (Foreground Service opcional para que Android no mate el WebSocket al cambiar de app).
  - **Mantener pantalla encendida** mientras supervisas un agente.
  - **Mejoras táctiles CSS** (desactiva rebote, scroll suave en bloques de código; se puede desactivar al vuelo).
- **Notificaciones nativas** puenteadas desde `WebNotificationDelegate` de GeckoView.
- **Subida de imágenes/archivos** (`PromptDelegate.onFilePrompt`) y **descargas directas a Descargas** (`MediaStore.Downloads`).
- **Doble toque atrás para salir** para no cerrar la app sin querer al deslizar.

## Compilar el APK

```bash
./build-apk.sh                 # Genera AntigravityRemote-arm64.apk (móviles Android modernos)
./build-apk.sh --install       # Compila e instala por ADB en el móvil conectado
./build-apk.sh --universal     # Genera AntigravityRemote-universal.apk (todas las arquitecturas)
```
