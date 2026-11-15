# paellas.net para Android

La APK es una capa nativa mínima alrededor de la web https://paellas.net, igual que
Inhouse Learn (`miguelcoxcaballero/inhouselearn/android`). Cada cambio de `index.html`
publicado en `main` llega a la app sin APK nueva; solo hace falta APK nueva para cambios
en `android/`.

## Qué hace la capa nativa (`MainActivity.java`)

- WebView a pantalla completa, de borde a borde, entre las barras del sistema y por
  encima del teclado. La barra de estado queda sobre el amarillo de la banda.
- Pantalla de inicio con la paella hasta que la web ha pintado (máximo 2,5 s).
- Puente `window.PaellasApp` solo para el origen `https://paellas.net` y el marco
  principal (`WebMessageListener`, nunca `addJavascriptInterface`). Mensajes:
  - `signIn`: abre el inicio de sesión de Google en una Chrome Custom Tab (Google no
    permite su login dentro de un WebView).
  - `save`: los PDF de entradas que genera la web, con el diálogo «Guardar como».
  - `open`: enlaces externos en una Custom Tab o en su app.
  - `updates`: abre los ajustes del actualizador (menú de perfil → «Actualizaciones»).
  - `nav`: la web manda las pestañas de la pantalla en la que está (`items`: id,
    texto e icono), cuál está activa y si la barra inferior debe verse. Inicio:
    Paellas, Archivos, Papelera; una hoja: Grupos, Tabla, Entradas, Historial; un
    formulario: Formulario, Respuestas, Ajustes; una paella no tiene pestañas.
  - `profile`: el avatar de la cuenta abre la hoja nativa de perfil.
  - `haptic`: vibración nativa de Android (`tap`, `soft`, `confirm`, `reject`) al
    pulsar controles; respeta el ajuste «Respuesta táctil» del sistema.
- Barra inferior nativa en móviles (`NativeNav.java`) con las pestañas que manda la
  web, la píldora amarilla animada y respuesta táctil; se aparta con el teclado, los
  diálogos, un grupo abierto y la hoja de perfil. Cada toque llama a
  `window.paellasNav(id)`. Es la misma lista que las pestañas de carpeta de la web
  en PC (`initPlaces` en `index.html`). La web oculta su propia barra cuando el user
  agent lleva `PaellasNav/1`; la APK 1.0.0 no lo lleva y sigue con la barra web.
- Perfil (`ProfileSheet.java`): hoja nativa con Actualizar, Salir y
  «Actualizaciones de la app». Se cierra tocando fuera, arrastrando o con Atrás.
- Botón Atrás: primero cierra lo que tenga abierto la web y luego sube un nivel de
  la ruta (documento → su paella → Inicio) con `window.paellasBack`.
- Cámara solo para `https://paellas.net` (Lector de entradas).
- Sin conexión: usa la copia en caché y, si no hay, «Sin conexión» con «Reintentar».

### Inicio de sesión con Google

Desde Android 1.0.2, las APK usan el módulo OAuth canónico de Inhouse Services
Updater, PKCE con el cliente Android de `com.inhouse.paellas` y el retorno
`com.inhouse.paellas:/oauth2redirect`. La sesión se renueva y puede reutilizarse
en Notes/Read mediante el proveedor cifrado, verificando paquetes y firmas.
Los permisos adicionales de Paellas siguen requiriendo consentimiento de Google.

Las APK anteriores mantienen el retorno web `https://paellas.net` →
`app-auth.html` → `paellas://auth`. Las webs comparten implementación, pero
mantienen sus sesiones separadas por origen. Consulta
`vendor/inhouse-services-updater/auth/README.md` para el contrato completo.

## Firma (importante para el futuro)

- Las APK se firman con **la clave permanente de Inhouse** (la de Inhouse YouTube,
  Instagram y Learn). Certificado SHA-256:
  `7133a4b3e4b9f9c4d2fbbd38b7dd2d44c7b8d0d7d16568bfe3da74a09e7887b6`.
- La clave está **solo** en el secreto **`EJEMPLO`** de `youtube-calma` e `ig-calma`.
  GitHub no deja leer ni copiar un secreto, así que este repositorio no firma: la APK se
  firma en `youtube-calma` con el workflow **Sign Paellas**
  (`.github/workflows/sign-paellas.yml` de ese repositorio).
- Nunca subas una clave, keystore o contraseña, ni firmes con otra clave: Android no
  instalaría la actualización encima.

## Publicar una versión

1. Sube `versionCode` y `versionName` en `app/build.gradle.kts` y súbelo a `main`.
2. En `youtube-calma`, ejecuta **Sign Paellas** con el SHA completo de ese commit.
3. Aquí, ejecuta **Android app** con «publish» y un changelog corto en inglés. Publica la
   release `android-v<versión>` y, tras verificar su descarga pública, la añade al canal A
   (`updates/A.json`). Nunca publica en B sin petición expresa (UPDATER.md).

El paquete `com.inhouse.paellas` es permanente. El actualizador compartido
(`app/src/main/java/com/inhouse/services/updater/`, `app/src/main/assets/updates/`) lo
escribe `tools/sync.py` del repositorio canónico: no se edita aquí.
