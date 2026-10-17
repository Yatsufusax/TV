# YuzuPlay — reproductor de listas .m3u para Google TV / Android TV

Reproduce los canales de tu lista, se cambia con el control remoto y se actualiza
desde GitHub cuando se cae algún canal.

## 1) Compilar el APK (una sola vez)

1. Instalá **Android Studio** (gratis): https://developer.android.com/studio
2. `File > Open` y elegí esta carpeta (`TVCanales`). Esperá a que termine el *Sync*
   (si pide actualizar Gradle o el plugin, aceptá la versión que sugiere).
3. (Opcional) Abrí `app/src/main/java/com/mistv/canales/Config.kt` y pegá en
   `DEFAULT_LIST_URL` la dirección *raw* de tu lista en GitHub (ver punto 3).
   También se puede poner después desde la propia app.
4. Menú `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
   El archivo queda en `app/build/outputs/apk/debug/app-debug.apk`.

## 2) Instalarlo en la TV

1. En la TV: `Ajustes > Sistema > Acerca de`, tocá 7 veces en *Compilación* para
   activar las opciones de desarrollador. Después activá *Depuración por USB/red*.
2. Desde la PC (con `adb`): `adb connect IP_DE_LA_TV:5555` y
   `adb install app-debug.apk`.
   Alternativa sin PC: pasá el APK con la app "Send Files to TV" e instalalo con un
   explorador de archivos (permitir "orígenes desconocidos").
3. Aparece como **YuzuPlay** en las aplicaciones.

## 3) Actualizar los canales por GitHub

1. Creá un repositorio **público** en GitHub y subí tu `lista.m3u`.
2. Abrí el archivo en GitHub, tocá **Raw** y copiá la dirección. Se ve así:
   `https://raw.githubusercontent.com/TU_USUARIO/TU_REPO/main/lista.m3u`
3. Pegala en `Config.kt` antes de compilar, o en la app: **flecha derecha** del control
   → *Cambiar dirección de la lista*.
4. Cuando un canal se caiga, editá `lista.m3u` desde la web de GitHub (lápiz ✏️) y
   guardá (*Commit*). La app descarga la lista nueva sola al abrirse, cada 6 horas, o
   al instante desde el menú → *Actualizar canales ahora*.
   Si no hay internet o falla la descarga, sigue usando la última lista guardada.

## Controles

| Botón del control | Qué hace |
|---|---|
| Flecha arriba / CH+ | Canal siguiente |
| Flecha abajo / CH− | Canal anterior |
| Números (0‑9) | Ir al canal por su número. Se confirma solo al instante o con OK |
| 0 | Volver al canal anterior |
| OK / Flecha izquierda | Lista de canales (con número y grupo) |
| Flecha derecha / Menú | Actualizar canales / cambiar dirección de GitHub |
| Info | Muestra el cartel del canal |
| Volumen / Mute | Los maneja la TV como siempre |
| Atrás | Cierra la lista; dos veces seguidas sale de la app |

## Números de canal

Cada canal toma su número según el orden de la lista (1, 2, 3 …). Para que un canal
**conserve siempre el mismo número** aunque agregues o quites otros, ponele
`tvg-chno="12"` en su línea `#EXTINF`:

```
#EXTINF:-1 tvg-chno="12" group-title="Deportes",ESPN
```

Los canales sin `tvg-chno` ocupan los números libres en orden.

## Canales de respaldo

Para darle un respaldo a un canal, repetí su línea `#EXTINF` con **el mismo nombre y grupo**
y otro link. La app los junta en **un solo canal** (no aparece repetido en la lista):

```
#EXTINF:-1 group-title="Deportes",ESPN
http://link-principal/index.m3u8
#EXTINF:-1 group-title="Deportes",ESPN
http://link-de-respaldo/index.m3u8
```

* El primer link es el principal; los siguientes se prueban en orden si el anterior falla.
* Si un respaldo anduvo, la app lo recuerda para ese canal hasta que se cierre.
* Si fallan todos, muestra "Sin señal" y vuelve a intentar desde el principal cada 10 s.
* "Probar canales" prueba todos los links y marca un canal como caído solo si caen todos.
* Cada link puede llevar su propio `#EXTVLCOPT` (referer / user-agent).
* Se admiten links `.m3u8` (HLS), `.mpd` (DASH) y TS directo.

## Si un canal se congela

La app vigila la transmisión cada 4 segundos. Si la imagen se queda quieta (aunque suene el
audio) o se queda cargando más de ~12 segundos, **reconecta sola** el mismo link. Si se
congela 3 veces seguidas, pasa al siguiente link de respaldo del canal.

## Notas técnicas

* Reproductor: Media3 / ExoPlayer. Admite HLS (.m3u8) y transmisiones directas TS.
  Si un canal no abre como HLS prueba como TS y viceversa.
* Respeta `#EXTVLCOPT:http-referrer` y `http-user-agent` de cada canal.
* La lista que viene dentro de la app (`app/src/main/assets/lista.m3u`) se usa solo
  hasta que se descargue una de GitHub.
* Los enlaces de YouTube (`youtube.com/...`) no se pueden reproducir.

## Código de acceso

La app pide un código la primera vez que se abre en cada equipo. Hay dos tipos (solo guarda las huellas, nunca los códigos):

- **Permanente:** se pide una sola vez y no vence. Para cambiarlo: `python3 tools/generar_codigo.py permanente` (o con el código que quieras al final).
- **Mensual:** uno distinto por mes, al azar. Sirve solo durante su mes: al terminar, la app lo vuelve a pedir y hace falta el del mes nuevo.
  Generar más: `python3 tools/generar_codigo.py meses AAAA-MM CANTIDAD` (ej: `meses 2028-10 24`). Los códigos quedan en
  `codigos_mensuales.txt`, que tenés que guardar en privado y **no subir a GitHub**. Cuando se acaben los meses cargados hay que
  publicar una versión nueva con más.
- Se acepta con o sin guion y en mayúsculas o minúsculas. Después de 5 errores seguidos la app espera un rato antes de dejar probar de nuevo.
- Si atrasan el reloj del equipo para reusar un código viejo, la app sigue usando la fecha más alta que ya vio.
- **La dirección de la lista ya no se carga a mano ni aparece en ningún menú.** Está dentro de la app cifrada una vez por cada código
  (el permanente y los mensuales), con una llave que sale del propio código. Al escribir un código válido la app la descifra y la guarda.
  Sin un código válido, lo que queda en el APK no se puede leer. Al generar códigos hay que pasar `--url DIRECCION` (mirá `tools/generar_codigo.py`).
  Si algún día cambiás de gist: `python3 tools/generar_codigo.py url DIRECCION_NUEVA --permanente TU_CODIGO` (con `codigos_mensuales.txt` en la carpeta).
- `app/src/main/assets/lista.m3u` es solo un marcador: la lista real se baja de tu gist apenas se abre la app.

## Actualización obligatoria

La app busca una versión nueva al abrirse, al volver de otra pantalla y cada 1 minuto mientras está abierta (`UPDATE_CHECK_MS` en `Config.kt`).
Para que una versión **no se pueda posponer**, poné `[OBLIGATORIA]` como primera línea de `NOVEDADES.txt` antes de subirla.
Ese cartel solo tiene los botones "Actualizar ahora" y "Salir", muestra una cuenta regresiva y, si no se actualiza a tiempo,
la app se cierra sola (`FORCED_CLOSE_SECONDS`, 60 segundos). Si se cancela el instalador o falla la descarga, el cartel vuelve a aparecer.
(Las versiones que ya estén instaladas sin esta función siguen mostrando el aviso normal, con "Ahora no".)
Si el repositorio es privado, la app no puede ver la versión nueva: dejalo público unos 10 minutos después de que Actions termine.

## Menú y avisos

El menú (`MenuDialog.kt`) es un panel centrado con una cuadrícula de opciones con icono y funciona con el control remoto y con toque.
Los iconos están en `res/drawable/ic_m_*.xml`. Los avisos de actualización usan `CenterDialog` (en `UiKit.kt`), siempre en el centro.

## Fuentes (elegir el enlace a mano)

*Menú → Fuentes* muestra los enlaces del canal que estás viendo (los que tienen el mismo nombre en la lista): "Automático" y "Fuente 1, 2, 3…".
- Al elegir una fuente se reproduce enseguida y queda **fijada** para ese canal: la próxima vez que lo abras empieza por esa. Si falla, la app sigue probando las siguientes como siempre.
- **Automático** borra la fijación y vuelve al comportamiento de siempre (la primera que ande).
- Cada fuente muestra si se está reproduciendo, si está fijada y si quedó "marcada caída" en la última prueba de canales. Se puede elegir igual.
- Si una fuente marcada como caída vuelve a andar, se le saca la marca sola.
- No se muestran las direcciones de los enlaces, solo "Fuente N".

