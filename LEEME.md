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
   (También queda publicado sin zip en *Releases → APK actual → separador-voz.apk*, se reemplaza en cada compilación.)
4. Pasa el APK al teléfono e instálalo (permite "instalar apps desconocidas" cuando lo pida).

### Opción B: Android Studio
Abre esta carpeta → espera la sincronización de Gradle → *Build → Build Bundle(s) / APK(s) → Build APK(s)*.
El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Cómo funciona
- Lee mp3, m4a, ogg, flac, wav… (lo que entienda Android) y todo se hace en el teléfono, sin internet.
- Separa por bandas de frecuencia con técnicas clásicas (no es una IA):
  1. **Estéreo:** lo que suena igual en izquierda y derecha (normalmente la voz) se toma como voz.
  2. **Armónico vs percusivo:** los golpes (bombo, caja, platillos) se dejan fuera de la voz aunque estén al centro.
  3. **Rango de frecuencias:** bajos y agudos extremos se quedan en la instrumental.
- Usa dos máscaras distintas: una **estricta** para la pista de voz (que no se cuele instrumental) y una
  **más agresiva** para quitar la voz de la instrumental (que no se cuele voz).
- Tarda desde unos segundos hasta ~1-2 minutos por canción, según el teléfono.

## Límites (importante)
- **No es una IA** (tipo Spleeter/Demucs). Con instrumentos al centro que suenan parecido a la voz
  (órgano, piano, sintetizadores) no se puede distinguir; y donde la voz coincide en frecuencia con un
  instrumento fuerte, queda algo mezclado. Es normal que la instrumental conserve un rastro de voz.
- Canciones **mono** no se pueden separar con este método (la app te lo avisa).
- La reproducción se pausa al salir de la app.
- Canciones muy largas (más de ~12 min) pueden no caber en la memoria del teléfono.

## Pruebas del algoritmo (en PC)
En `tools/test/...` hay pruebas con señales sintéticas (`javac` + `java`, sin dependencias):
`SeparatorTest` (comprobaciones básicas), `SceneBench` (compara la versión anterior con la actual en una
escena con voz, batería, bajo, guitarras, pad y órgano) y `SceneSweep*` (barridos para ajustar parámetros).
