package com.mistv.canales

object Config {
    // Dirección "raw" de tu lista en GitHub. Ejemplo:
    // https://raw.githubusercontent.com/TU_USUARIO/TU_REPO/main/lista.m3u
    // También se puede cambiar después desde la app (flecha derecha del control).
    const val DEFAULT_LIST_URL = ""

    const val OSD_MS = 4000L              // cuánto dura el cartel del canal
    const val DIGIT_COMMIT_MS = 1800L     // espera al tipear números
    const val AUTO_UPDATE_MS = 6L * 60 * 60 * 1000  // actualizar cada 6 horas
}
