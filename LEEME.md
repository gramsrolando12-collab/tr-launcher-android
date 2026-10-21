# T.R Launcher para Android

La app Android de T.R Launcher: **la misma interfaz que el launcher de PC** (logo T.R, Inicio, Skins, Perfil, Ajustes,
Servidores y Panel) y por debajo un motor abierto que sí puede abrir Minecraft Java en el celular.

- Usa **tu mismo servidor de cuentas** (`https://tr-launcher-api.tintino12.workers.dev`): la cuenta, el rol y la skin son iguales en PC y celular.
- **No incluye Minecraft**: igual que en PC, la app lo descarga desde los servidores oficiales de Mojang (Minecraft 1.21.11 + Fabric).
- El mod de skins (CustomSkinLoader) se baja solo la primera vez, y el juego se pone en español.
- Los servidores «Nunca Jamás» y «Nunca Creativo» están dentro de la app.

> **T.R Launcher es una versión modificada NO oficial de Zalith Launcher 2** (motor de código abierto, licencia GPL-3.0,
> ver `LICENSE`). Por la licencia este repositorio debe ser **público**. Tu servidor y tu base de datos NO están aquí.

## Qué hay en este repositorio

| Carpeta / archivo | Para qué sirve |
|---|---|
| `overlay/` | Todos nuestros archivos (la interfaz T.R, el puente, las cuentas, el icono). Se copian encima del motor al compilar. |
| `.github/workflows/apk.yml` | La «receta» que descarga el motor, le pone nuestros archivos y compila el APK en los servidores de GitHub (gratis). |
| `LICENSE` | Licencia GPL-3.0 del motor. |

La versión exacta del motor está fijada en `apk.yml` (`MOTOR_COMMIT`), así nada cambia solo.

---

# GUÍA PASO A PASO (para quien nunca usó GitHub)

Tiempo total: unos 20 minutos de trabajo tuyo + 20 a 40 minutos de espera mientras GitHub compila.

## PARTE 1 · Crear tu cuenta en GitHub (solo la primera vez)

1. En la PC abre **https://github.com** y pulsa **Sign up**.
2. Pon tu correo, una contraseña y un nombre de usuario. Confirma el código que te llega al correo.
3. Cuando pregunte cosas de «personalizar», puedes pulsar **Skip**.

## PARTE 2 · Crear tu repositorio (la carpeta en línea)

1. Ya con la sesión iniciada, arriba a la derecha pulsa el **+** → **New repository**.
2. **Repository name:** escribe `tr-launcher-android`.
3. Marca **Public** (obligatorio por la licencia; es gratis).
4. NO marques nada más. Pulsa el botón verde **Create repository**.

## PARTE 3 · Subir los archivos

1. En tu PC, haz clic derecho en el zip que te mandé → **Extraer todo**. Se crea una carpeta `TR-Launcher-Android`.
2. Abre esa carpeta. Debes ver: la carpeta `overlay`, la carpeta `.github` (si no la ves: en el Explorador, pestaña
   **Ver** → marca **Elementos ocultos**), `LICENSE` y `LEEME.md`.
3. En la página de tu repositorio recién creado pulsa el enlace **uploading an existing file**.
4. **Selecciona TODO lo que hay dentro de la carpeta** (Ctrl+A) y **arrástralo** a la zona de la página de GitHub
   (las carpetas también se pueden arrastrar). Espera a que termine de cargar (verás la lista de archivos).
5. Abajo pulsa el botón verde **Commit changes**.
6. Comprueba: en tu repositorio debes ver las carpetas `.github` y `overlay`. Entra en `.github` → `workflows` y
   comprueba que existe `apk.yml`.
   - **Si falta `.github`** (a veces el navegador no sube carpetas ocultas): pulsa **Add file → Create new file**,
     en el nombre escribe exactamente `.github/workflows/apk.yml` (al escribir la `/` se crean las carpetas), abre el
     archivo `apk.yml` de tu PC con el Bloc de notas, copia todo su texto, pégalo en GitHub y pulsa **Commit changes**.

## PARTE 4 · Compilar el APK

1. Arriba, en tu repositorio, entra en la pestaña **Actions**.
2. Si aparece un botón verde **I understand my workflows, go ahead and enable them**, púlsalo.
3. A la izquierda pulsa **Compilar APK de T.R Launcher**.
4. A la derecha pulsa **Run workflow** → y otra vez el botón verde **Run workflow**.
   (Si ya se inició sola al subir los archivos, no hace falta: verás una fila con un círculo amarillo girando.)
5. **Espera 20 a 40 minutos.** Puedes cerrar la página y volver luego. Círculo amarillo = trabajando,
   ✔ verde = listo, ✖ rojo = falló.

## PARTE 5 · Bajar el APK

1. Pulsa sobre la fila con el ✔ verde.
2. Baja hasta el final de la página, sección **Artifacts**, y pulsa **TR-Launcher-APK**. Se descarga un `.zip`.
3. Descomprímelo: dentro está el archivo `.apk` (pesa varios cientos de MB porque lleva el motor de Java dentro).

## PARTE 6 · Instalarlo en el celular

1. Pasa el `.apk` al celular (por cable USB, Google Drive, o WhatsApp «enviar como documento»).
2. Ábrelo desde el celular. Si te dice que no se pueden instalar apps de esta fuente, pulsa **Ajustes** y activa
   **Permitir de esta fuente**; vuelve atrás e instala.
3. Si sale el aviso de Play Protect, pulsa **Instalar de todos modos** (es normal: la app no viene de Play Store).

## PARTE 7 · Usarlo por primera vez

1. Abre **T.R Launcher**. Arriba a la derecha pulsa **Sin cuenta** → entra con **el mismo nombre y contraseña** que
   usas en PC (o crea una cuenta nueva ahí mismo).
2. Pulsa **Descargar Minecraft** y espera. Hace falta Wi-Fi y unos 2 GB libres. Solo se hace una vez.
3. Pulsa **JUGAR**. La primera vez tarda más porque prepara Java y las skins.
4. Para entrar directo a un servidor, ve a **Servidores** y pulsa **Jugar**.

## ¿Y si falla la compilación (✖ rojo)?

Es posible en la primera vez: esta versión se escribió sin poder compilarla en un Android real.

1. Pulsa la fila roja → pulsa el cuadro **apk** (a la izquierda).
2. Abre el paso que tiene la ✖ roja (casi siempre **Compilar el APK**) y copia las últimas ~40 líneas, sobre todo las
   que empiezan con `e:` o dicen `error`.
3. Mándamelas por este chat y lo corrijo; luego repites solo la **PARTE 4**.

## Cambiar algo después

- **Dirección de tu servidor de cuentas:** en GitHub abre
  `overlay/ZalithLauncher/src/main/java/com/movtery/zalithlauncher/tr/TrConfig.kt`, pulsa el lápiz ✏️, cambia `API_URL`
  y **Commit changes**. Se vuelve a compilar solo.
- **Servidores, textos o imágenes:** están en `TrConfig.kt` y en `overlay/ZalithLauncher/src/main/assets/tr-ui/`.

## Avisos importantes

- Para jugar hace falta un celular decente (4 GB de RAM o más). Minecraft 1.21 en celular es pesado.
- El nombre único solo se garantiza dentro de las apps T.R. Si tu servidor Aternos está en modo «sin licencia», quien
  entre con otro launcher puede escribir cualquier nombre; la protección real es un plugin tipo AuthMe/EasyAuth o whitelist.
- Nunca subas a GitHub tus claves (por ejemplo `CLAIM_KEY`): este repositorio es público.
