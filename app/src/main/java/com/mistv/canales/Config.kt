package com.mistv.canales

object Config {
    // Dirección "raw" de tu lista en GitHub. Ejemplo:
    // https://raw.githubusercontent.com/TU_USUARIO/TU_REPO/main/lista.m3u
    // También se puede cambiar después desde la app (flecha derecha del control).
    const val DEFAULT_LIST_URL = ""

    // Archivo independiente de eventos (JSON) en el mismo repositorio.
    // Editá eventos.json en GitHub para agregar/cambiar canales sin recompilar el APK.
    const val EVENTS_URL = "https://raw.githubusercontent.com/Yatsufusax/TV/eventos-pruebas/eventos.json"

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

    // Zapping rápido: cuánto video se guarda antes de empezar / reanudar (milisegundos).
    const val BUF_MIN_MS = 8_000
    const val BUF_MAX_MS = 30_000
    const val BUF_START_MS = 1500         // ExoPlayer: video juntado antes de empezar (antes 700)
    const val BUF_REBUFFER_MS = 5_000       // ExoPlayer: video juntado antes de reanudar tras un corte (antes 2000)
    const val VLC_CACHE_MS = 3000         // solo motor VLC: caché de red (antes 1000)

    const val INFO_MS = 9000L             // cuánto dura la pantalla de información
    const val OSD_MS = 4000L              // cuánto dura el cartel del canal
    const val DIGIT_COMMIT_MS = 1800L     // espera al tipear números
    const val AUTO_UPDATE_MS = 6L * 60 * 60 * 1000  // actualizar cada 6 horas
    const val UPDATE_CHECK_MS = 60L * 1000          // buscar versión nueva de la app cada 1 minuto
    const val FORCED_CLOSE_SECONDS = 60             // en actualización obligatoria: segundos antes de cerrarse si no actualizan

    // Código de acceso: se pide una sola vez en cada equipo. En la app solo está su "huella", no el código.
    // Para cambiarlo: python3 tools/generar_codigo.py  (y pegar las dos líneas que imprime)
    const val ACCESS_SALT = "6a801fe14c4aa723"
    const val ACCESS_HASH = "7e3875e70246859354da8a16af2bd4c236a74636c693c49a5a8191c37cecfb19"

    // Códigos mensuales: uno distinto por mes, al azar (formato "AAAA-MM:huella;"). Sirven solo durante su mes.
    // Se generan con: python3 tools/generar_codigo.py meses AAAA-MM CANTIDAD
    const val MONTHLY_HASHES =
        "2026-10:f0e423be9ccfae21e81d061dba6463dc307d9df357fdca8b29857445d66ad8d6;" +
        "2026-11:2aa587117119109eb08d60181806faacb0be7af4e8e8259f2854a7254814928c;" +
        "2026-12:6baaadaa8d437e6715de9d41e81abba3cd5b0873910ea6b5554d53bf1a3f7053;" +
        "2027-01:42004a53d4191de7caf3ddbdc4614c45b4ab0a87cff3ce6d4b49f551310ac8f5;" +
        "2027-02:43b0b66258b2a8845305cce41c3d08304c083a98878fdbd35bb1f58a1323fc6b;" +
        "2027-03:dbd32863a99131883922eb3fe7324ced3aed4c7c1e1e5d224f50ae6ca8e4e319;" +
        "2027-04:c28c8874b707508fa2129a7feedda51b419f840e98a3d9dc9ecc611a45a043f3;" +
        "2027-05:b5f409bda1d4ffe718dd1e972b4e4125545e285da8b4935434ab9b08a51a9db6;" +
        "2027-06:bc75acf90013585febf3fdafee09bcbe63a823880a2dd317004aa9983eb5eaef;" +
        "2027-07:c6668ff8d4ab701cdcfd5c2191f2bfe3d921293bff87d8f75fee3a3749530008;" +
        "2027-08:4602ad05997915c17d191f9b37b38fcbff8df914e7417997466108d15a62e237;" +
        "2027-09:4e1f4fc0e2fd15704e787ca0d970f2bc885d582f479f19a974620d0ebbabdb1d;" +
        "2027-10:9853659ff93f4e25c0033971e85412101487a0f1415379ab8909deaf64099424;" +
        "2027-11:19d2953f89a06510b9fb9a9cb06969a1a27fa974c9923a9ba28c17ec3ca3ffd4;" +
        "2027-12:daa5b5d86094bf7fa3d475f9161bb77b627d6dcf39dc624d53a49fac0b175f73;" +
        "2028-01:fe6e6ae3458547db0aa643161f1c981cd75d3ba1acd90b0cc89459127894382b;" +
        "2028-02:2863e2e2abd98d92835399ba51ba62e2498be83f90c9ce65c35fb4b20190088f;" +
        "2028-03:88d9fbda3accac256844521610c67161fe37d20fa876eaed69f2e15ea4e4a071;" +
        "2028-04:d464ea1cdf14d5eecd36190ca8f64baf3d14ef332c8edc7c72897ac456a70a6c;" +
        "2028-05:84254bcfdf536cfd67a66c6df32a3322e021a3a66c3e29d8f84414d17fe37262;" +
        "2028-06:f081943872c7e796c8ca0d76a3b8b5c89b23972e92057272521e404ceaee5d8b;" +
        "2028-07:c50521f808ef0b17b500f4f0267eea6b5147f4239180ece13fcaf93590d7981f;" +
        "2028-08:43734b0b7dc260b0597facab825a2f0bc20796bbd12f1552d9886521c90e50e4;" +
        "2028-09:4e4301b53c12da7a18c5b14ab6e0338d7945c534f65e3ea8eaab3e8e529e9ace;"

    // Dirección de la lista, cifrada una vez por cada código (formato "perm:..." y "AAAA-MM:...").
    // Se genera con: python3 tools/generar_codigo.py url DIRECCION --permanente CODIGO
    const val LIST_BLOBS =
        "perm:A+3O/vzQTexs+SwRMxyaVCL8hLD/yrTVJ8c8408IVtqUGqUTDKXZnOZ1ard/k3ZVmEnk9FeVbyNnUNP6g2kDbfrdHNxEDIx9oo5nb4hRgzdAmI5w+W98IiO1gKd1Uc6wSoHLc2RsI2GQWYyx9nn0l/x1SOdeTXviwPLkOkF16gz7AK7Z5Cvk79eHyo8=;" +
        "2026-10:dY/ryxLl2O8cS8F7JI8rkBwVIFWf7AKMeVxe/arXSrIJDYiowlqLds530KtjMT/tjsMLtrsUm9DqhJ+7LsGpU0CgGXUl15Iuie5S2J4F2TpOK1Jf8U/BgwoRlSol82bPLCEqDWSk+SQy7eDBrOumktzFBFascrFiWtJA97/L7wHzReSqp+j5esnET7o=;" +
        "2026-11:1Msye87kZFHHw/HXPqSqLyyayV0P/EPow8q4z0AzdSS0tLhELOfu8gqo6ANQfy2sqz0hludzc1+TqffF5GSNfka86qgWslF6Nei2bWHdRcNsVwyEE252aroVQVxVGFr/a/bxHLD2EWLHMJpNT9RFPHMexX5yJviLCjXcis5HsAi2qdbjCSwkSN29JCs=;" +
        "2026-12:PrWOmTKd1hGBq2p3W0+oiDOtuRktAkk0IFQRXVIrrycRBiuQrxa3R1kgNnfCxNDQfHiVA+xvK0L5n9/z7CXGmAxcV5abSkw7zqfVAT9OAKsJ5NekecW9IambZsyJL1l9qVNS0CMs8N/8qXOWbbUFJYbWC/BIHlq/2BOHWN/OXH4CCWsJ5+0m6qAtcJM=;" +
        "2027-01:Q0G2l2zdm2qcizxoEn6m8DtIeaOvGFD/DfdoEoBWEyfNS64Q2axHt9/6CFAq5geqoAN/NKLsyzTcWPDguTSceJtjXa61ZZKTB62KswLD2sGav9RNJ3dFzf7Pw5VThm2hfFNbeA5yjcBzjbU7yxhYJa2veWsCh1NSEjBX+wdSDKQHhu4YijGzQ6CpFC4=;" +
        "2027-02:AOAVkJCFOojWtymglnqWpBLem2O2oKJ/f8usASUGQV+07i5acQBjKq3lMqkKdROwqOW+7ndICJDXgDRaqjsrH3VSYRnCpUIPL2QsAlGJKlm56FjKU/+VMm3umh1q9PIzc3WtBliAjOB+C6kASfhyNWpyKfLjCKDE3RszeAfhwB1lWioZ5H+4fd6rIBc=;" +
        "2027-03:te4BsULAY8yW4NS0aw7EYWFzqQsEWwMwsBuYAJPXBviq7reO1A2HgGpi7KWpss05OahIvzjkz4biNrNwt9COPfBVsAeuf7ROyTmn7zSlsMcqsYTICI5o6x6QLGgNWcgfkd9ihQMjgEnMPvM21Qlf6SB7wFLMaQFVEIsqqRmnw6d1Ypzsild9ucTbqFo=;" +
        "2027-04:Q6Yo1mkDGPSGkx5BQqJjJ9oIF6E5pPsh55jfyI2IHWcvi+0KwG9UIPPUdZ2UxKhCU4Lm2TKTFDy4lJxdGETpWGo1FEc2sUabocpnesh/NEPc2K68c5MgCq6PJkVr0n0PFkxFpZzlSvv7hbqscPAPe6z90uZCV/f6AAn1D+TD/c2HVO9rAXJ10Sis0l0=;" +
        "2027-05:07WuQlFhz72mpLO2cjFpKppPH3CwuTx5fZjK8PCgqaxdJHTbBMl7HauKUXYPIAAunpyGzmn3MEuzDeZeacoIFEeOXqMoGUtjcDLQ+QjPMK5dsU+CU21+GwuxPCvcPzA4T6Ir0evC09JjhAaI8BMCDqrFCmcVXSwUD5GxRVk8TwhQ4bGdaiNhq9vjAo8=;" +
        "2027-06:gB1TqDm774LNbClayJUVDGE5793xMRz3RObDy2Q7U8nw2a3R0kaMhxytaTdk2ZRir9zJRXz834JVHNPkqwPrKlE+PhnKNBZ+ThEnZXzwWIJbIvOk6JVpkTq5tjLSl+Qo+GOVsQr/FuSOmBiY171mOwQcNGGCVdPxe95ToWz8PBKP1q0oIbI9Sfubk7Q=;" +
        "2027-07:JvU173VwepUBQHA0K7c3vDzAs+FK712WR09K8TDCGge4v8AyD3Dai+DJaEDgVzq7rNDn8S2TTmMbtc1OGnPqA54Rp+KLFvhYEURrLrm9c479PJh75AMIF4dvIWVlnmcgqqWyPBYmw1OZCGjghtWuu7wK2smaNDAyjZVGhnKyXjVXzcnL97v1kACC8hI=;" +
        "2027-08:Xuj4KaucK89ZDpUjxBlcQnpLeG7shnn+8FpJ3XYOOWkdwdK399nrIdHOfu1vnEcc0JLhQwLkBjMzrEd4NWjMiJhS+1+Ew0nAf9mBYllRbTaQuCdchrQiPrb0dJfsze6UlV79x+Lb7ZofFEs+0FEDCtIMHEQ+g4KwGXhvDHOlSDsj6hQ8mNIqrPlnOkg=;" +
        "2027-09:EsZMcxYmQn+ui/jViZKGBnh5VD/BKXeXDL9TWMHL9FAg7RcRaEvrANxOAenCv42siwg15SNgUzgvMBM4RMcVFrI35waFWYmTmZOkg1SUzrkufl7EzsadzrpcAtdlvVsZMaIFCZQ3XAbbo4h0rbGTEoSFQu5hN/+VoeEp5TOiNoF0x3Fms3wRroW6lfE=;" +
        "2027-10:TKZ4arpbuoxfQpDBbEWY5WlqSCYH4z02w8tIyM6XuqP9OvJ7wgzti2XA3QxcolTt4u6iuBfgix0OL8Qj24seyBkDIerg2+3NtZR6B22zCA/aSGEBBZgyMoQtVLB+hjHckeziCgBQC/M8P/j5IMr/lc/cnFzzBvDpfjqTHVuDJnlCsNrgsslbsFD62Fw=;" +
        "2027-11:Puaa1wV1OWrFBjJKYaJPH3nx/HpRcr8jlCOLNlnflK6AHEs5dV+yCxBkdFAnqcVpKmVcM9L9fkLExFcY6Z4QAoDjrMGHEyQ01h/KSox5KANeT/BRu76J6k978zXMzjyRpjq+o0FpHJxzCuH5Ws/AXnNZNseV8lcUHX6LOKVewzBhVvr67DC6NBdbl5Q=;" +
        "2027-12:FemwfdecPbpmDhkkf7imiSQJGEfKVdbfIEsdBMztDxfbwy+3rwCsRMSv0uKhfK24xNNVu7UaDr9KHwVlI/4upOznw1sCRzGHjSMrQlH/eMrDdKdRlNZKNoIV96Pl9EMUx1JKOgLgU27eKw8E152+9sDbFnEHRB9DNfJ0RJSZsQwaN4A3jloF1bBny2s=;" +
        "2028-01:B2K9EOak0TPwoq6kSzxAJB6efjUzDflqR0T/BarSsDI4tWmTDb0KezXbPMC9+zKhUA/LLR3UKhqf/aCbtdC2lEVOrdC4cxBW4k51En/pd5XLH20N0kcgvufM57gYkW8BqfhV4m2MD/RkJ4c2rYBEWQp65ZHRki5LHCWdn2INCKKheqwkwoFTqFy+T/Q=;" +
        "2028-02:aCbT8TQQZTPVZXpxid4/2F6WCHA9SWFykXyIMmxEZm6gon6NRKHrYen5vgC0UthWrNcaMWAAHl9wxWn7E+gml7/fyH0ss5pfmWXejA0IgY6B2tWovn0SzfmdjfuJ+XdcWJyafdtZdEuQ1zN7Lp2PyGNjC/Y+qJuhfoI68RB3nPcixkGibNnjp85P1eY=;" +
        "2028-03:WLPh+vxpFJCaTjron9rcyQOz6r/fSuUzkPW6B6g8NmtPh1Mz+zY0R1YUTEaQYqMHTnWiIgfKpxUZOag45tZWYyjjuIX7NeYBJOLNc0OLB39V0MphPLwBG/yOso6dmxQIP26EAsIjFoZ9zmyu56o2YyLuM43/fMkwvhwVTkVCaZWAvRtGS25bUjt4N1g=;" +
        "2028-04:O1UWjq89YxOArnCmbNs1Ufx/JUPejPMQCp2E5YojyW1PH6IFT0kUliZqZxLbZf+xPHT1JXx/qCRu2qsXuDInQuLzG+JCqgxXD7ePtJchOX0LwjFUKqmIHxyV3ZAig+d0rja+/yg/pc3jB42DuKTpj8wn44csqaLG5MVSY+g86mKnXvHGGbfzVs/m5Tw=;" +
        "2028-05:if+aetifaxZ7tCG6mJIzoy4Rk+74YYmMXscPFeTpMy8Iubhu2cwWvSCVTyFvzTbKfFyBOlUkdu4SHphws/8CC0TQXdOVaPvOOdtCZ18f3+LJOfNTJvNmjR4jfudiSS/nbwr5Cz77ksa0R4tNZGSHvIwwSEfIawN8b2oixwo2M/fqMEiuqH9UgVgzOuc=;" +
        "2028-06:5saasgGYc5nyRmWaXsusJwEzxKw+34Gb1HPhWAYptHPeWuHkC/oN2XZO+8K2n0KG5hbTu2Zkw28nZMEvByxs9+cQjUtX8buiN2PfZqoKt9/bLOW52mCfb4btoM2+/Yb/IPheXP/DMJYJejui9MNEN/8lOhmf9rw5rAEp46eyWLOE2SH0e+VO3/8Z6wI=;" +
        "2028-07:nQsgADIpJpaJMAn2XcuU2bR/kBvWNkj1lzHZ9fVhUeRq9VYOcNhKQeIK1xGGEYoTtV6+ceHXf4n0iSFJqqGvGZEcDcIE58MGHjUHitnLVThTVJRzq1A5MAWhUfGAg0DYo0DOFP/Uw2lft3rbDpEWlLK53n9kItv8L2I8NlAfHj4evOB+MjfVE1yB7pw=;" +
        "2028-08:bU2nmpwxUaXMw1uk5WsvvZb+FifdnoJA+m+VngujvO8WUOxhyjy0nwtT3PCJ2m6u5F3joguz2IVAPM0crRTmz/MIklCDyt5j3JeCthiHO+RiQYe7yBci3Dm0V0MsDcrGJbNVptGhY64Y5di4Hgdd437UjJeAGtvFllvKpYeSRR0O5HEN3YLNV/y89pM=;" +
        "2028-09:C09EzsJzocPEpTVVvRLWydFfPaWCBJ1bp/IDQ50hskrLB2teYnl4QZPwHPJ9pYqwej4fNyLyvzUC5j6pG3toP9wX6FL6IT1q8gnNj/i77mFL4irvyYcOItkDvbj7du4sDuLI4l0B14ZsXZ7kyzjG7Hn5qQ1QgeR7Af69HQV+/NjBJlD6kuCWkkWumMg=;"
}
