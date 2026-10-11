package com.mistv.canales

object Config {
    // Dirección "raw" de tu lista en GitHub. Ejemplo:
    // https://raw.githubusercontent.com/TU_USUARIO/TU_REPO/main/lista.m3u
    // También se puede cambiar después desde la app (flecha derecha del control).
    const val DEFAULT_LIST_URL = ""

    // Dirección donde GitHub publica la última versión de la app (Releases).
    // Formato: https://github.com/USUARIO/REPO/releases/download/latest/
    // (el repositorio tiene que ser público para que la app pueda descargarla)
    const val UPDATE_BASE_URL = "https://github.com/Yatsufusax/TV/releases/download/latest/"

    // Guías de programación (XMLTV) que se cargan por defecto, una por línea.
    // Se pueden cambiar desde el menú → Guía de programación (EPG).
    const val DEFAULT_EPG_URLS =
        "https://epgshare01.online/epgshare01/epg_ripper_AR1.xml.gz\n" +
        "https://epgshare01.online/epgshare01/epg_ripper_CL1.xml.gz\n" +
        "https://epgshare01.online/epgshare01/epg_ripper_CO1.xml.gz\n" +
        "https://epgshare01.online/epgshare01/epg_ripper_MX1.xml.gz"

    const val OSD_MS = 4000L              // cuánto dura el cartel del canal
    const val DIGIT_COMMIT_MS = 1800L     // espera al tipear números
    const val AUTO_UPDATE_MS = 6L * 60 * 60 * 1000  // actualizar cada 6 horas
}
