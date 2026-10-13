# Separador de voz / instrumental (app Android)

Eliges una canción de tu teléfono, la app separa la voz del resto y tú decides qué suena:
**canción completa**, **solo instrumental** o **solo voz**. Se puede cambiar en pleno reproducción.

## Cómo sacar el APK

### Opción A: GitHub (sin instalar nada)
1. Crea un repositorio nuevo en GitHub y sube **todo el contenido** de esta carpeta
   (cuida que se suba la carpeta oculta `.github`; si no, créala a mano:
   *Add file → Create new file* → `.github/workflows/build-apk.yml` y pega el archivo).
2. Ve a la pestaña **Actions** → **Compilar APK** (arranca solo al subir; si no, *Run workflow*).
3. Cuando termine (~4-6 min), entra a la ejecución y baja **separador-apk** (zip con `app-debug.apk`).
4. Pasa el APK al teléfono e instálalo (permite "instalar apps desconocidas" cuando lo pida).

### Opción B: Android Studio
Abre esta carpeta → espera la sincronización de Gradle → *Build → Build Bundle(s) / APK(s) → Build APK(s)*.
El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Cómo funciona
- Lee mp3, m4a, ogg, flac, wav… (lo que entienda Android).
- Separa por bandas de frecuencia usando el estéreo: lo que está igual en izquierda y derecha
  (normalmente la voz) va a "voz"; el resto va a "instrumental" (instrumental = original − voz).
  El bombo y el bajo (< ~90-180 Hz) se quedan siempre en la instrumental.
- Tarda unos segundos por canción (depende del teléfono) y todo se hace en el dispositivo, sin internet.

## Límites (importante)
- **No es una IA** (tipo Spleeter/Demucs): es un método clásico. Con canciones estéreo de estudio
  suele ir bien, pero puede quedar algo de voz en la instrumental, o algún platillo/caja en la voz.
- Canciones **mono** no se pueden separar con este método (la app te lo avisa).
- La reproducción se pausa al salir de la app.
- Canciones muy largas (más de ~15-20 min) pueden no caber en la memoria del teléfono.

## Pruebas del algoritmo (en PC)
`tools/test/.../SeparatorTest.java` prueba la separación con una señal sintética
(`javac` + `java`, sin dependencias).
