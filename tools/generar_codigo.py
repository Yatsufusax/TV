#!/usr/bin/env python3
"""
Códigos de acceso y dirección de la lista (todo cifrado). En la app solo quedan huellas y textos cifrados.

  python3 tools/generar_codigo.py permanente [CODIGO] --url DIRECCION
        código permanente nuevo (al azar si no ponés uno)
  python3 tools/generar_codigo.py meses AAAA-MM CANTIDAD --url DIRECCION
        códigos mensuales al azar (ej: meses 2026-11 24)
  python3 tools/generar_codigo.py url DIRECCION --permanente CODIGO
        cambia la dirección de la lista (por ejemplo si hiciste un gist nuevo). Usa el código permanente que le
        pasás y los mensuales de codigos_mensuales.txt (tiene que estar en la carpeta donde corrés el comando)

Cada comando edita Config.kt solo. Los mensuales se guardan en codigos_mensuales.txt: GUARDALO EN UN LUGAR PRIVADO
y no lo subas a GitHub. Un código mensual solo sirve durante su mes. Después de cambiar algo, subí el proyecto
para compilar la app nueva.

La dirección de la lista se cifra una vez por cada código, con una llave que sale del propio código: sin un código
válido, lo que queda en el APK no se puede leer.
"""
import base64
import hashlib
import hmac
import re
import secrets
import string
import sys
from pathlib import Path

CONFIG = Path(__file__).resolve().parent.parent / "app/src/main/java/com/mistv/canales/Config.kt"
ALFABETO = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"  # sin 0/O ni 1/I para no confundirse
ROUNDS = 100_000  # tiene que ser igual a ROUNDS en AccessGate.kt
VALIDOS = set(string.ascii_uppercase + string.digits)


def normalizar(codigo: str) -> str:
    return "".join(c for c in codigo.upper() if c in VALIDOS)


def huella(salt: str, codigo: str) -> str:
    h = (salt + normalizar(codigo)).encode("utf-8")
    for _ in range(ROUNDS):
        h = hashlib.sha256(h).digest()
    return h.hex()


def derivar(salt: str, codigo: str) -> bytes:
    h = ("K|" + salt + normalizar(codigo)).encode("utf-8")
    for _ in range(ROUNDS):
        h = hashlib.sha256(h).digest()
    return h


def hm(clave: bytes, datos: bytes) -> bytes:
    return hmac.new(clave, datos, hashlib.sha256).digest()


def cifrar(salt: str, codigo: str, texto: str) -> str:
    """nonce(16) + texto cifrado + etiqueta HMAC(32), en base64. Mismo esquema que AccessGate.kt."""
    master = derivar(salt, codigo)
    nonce = secrets.token_bytes(16)
    ek, mk = hm(master, b"enc"), hm(master, b"mac")
    datos = texto.encode("utf-8")
    ks = b"".join(hm(ek, nonce + i.to_bytes(4, "big")) for i in range((len(datos) + 31) // 32))
    ct = bytes(a ^ b for a, b in zip(datos, ks))
    return base64.b64encode(nonce + ct + hm(mk, nonce + ct)).decode()


def nuevo_codigo() -> str:
    c = "".join(secrets.choice(ALFABETO) for _ in range(10))
    return c[:5] + "-" + c[5:]


def leer_config() -> str:
    return CONFIG.read_text(encoding="utf-8")


def salt_actual(cfg: str) -> str:
    m = re.search(r'const val ACCESS_SALT = "([0-9a-f]+)"', cfg)
    if not m:
        sys.exit("No encuentro ACCESS_SALT en Config.kt")
    return m.group(1)


def siguiente_mes(y: int, m: int):
    return (y + 1, 1) if m == 12 else (y, m + 1)


def opcion(args, nombre):
    if nombre not in args:
        return None
    i = args.index(nombre)
    if i + 1 >= len(args):
        sys.exit(f"Falta el valor de {nombre}")
    valor = args[i + 1]
    del args[i:i + 2]
    return valor


def leer_blobs(cfg: str) -> dict:
    m = re.search(r'const val LIST_BLOBS =\s*((?:"[^"]*"\s*\+?\s*)+)', cfg)
    if not m:
        sys.exit("No encuentro LIST_BLOBS en Config.kt")
    texto = "".join(re.findall(r'"([^"]*)"', m.group(1)))
    d = {}
    for par in texto.split(";"):
        if ":" in par:
            k, v = par.split(":", 1)
            d[k.strip()] = v.strip()
    return d


def escribir_blobs(cfg: str, d: dict) -> str:
    d = {k: v for k, v in d.items() if v != "x"}
    claves = (["perm"] if "perm" in d else []) + sorted(k for k in d if k != "perm")
    bloque = "const val LIST_BLOBS =\n" + " +\n".join(f'        "{k}:{d[k]};"' for k in claves)
    return re.sub(r'const val LIST_BLOBS =\s*(?:"[^"]*"\s*\+?\s*)+', lambda _: bloque + "\n", cfg, count=1)


def permanente(args):
    url = opcion(args, "--url")
    if not url:
        sys.exit("Falta --url DIRECCION (la dirección raw de tu gist)")
    cfg = leer_config()
    salt = salt_actual(cfg)
    codigo = args[0] if args else nuevo_codigo()
    if len(normalizar(codigo)) < 8:
        sys.exit("El código tiene que tener al menos 8 letras o números.")
    cfg = re.sub(r'(const val ACCESS_HASH = ")[0-9a-f]*(")', r"\g<1>" + huella(salt, codigo) + r"\g<2>", cfg)
    blobs = leer_blobs(cfg)
    blobs["perm"] = cifrar(salt, codigo, url)
    CONFIG.write_text(escribir_blobs(cfg, blobs), encoding="utf-8")
    print("Código permanente (guardalo, no se puede recuperar):", codigo)
    print("Config.kt actualizado.")


def meses(args):
    url = opcion(args, "--url")
    if not url:
        sys.exit("Falta --url DIRECCION (la dirección raw de tu gist)")
    if len(args) < 2 or not re.fullmatch(r"\d{4}-\d{2}", args[0]):
        sys.exit("Uso: python3 tools/generar_codigo.py meses AAAA-MM CANTIDAD --url DIRECCION")
    y, m = int(args[0][:4]), int(args[0][5:])
    cantidad = int(args[1])
    cfg = leer_config()
    salt = salt_actual(cfg)
    blobs = {k: v for k, v in leer_blobs(cfg).items() if k == "perm"}
    filas, partes = [], []
    for _ in range(cantidad):
        clave = f"{y:04d}-{m:02d}"
        c = nuevo_codigo()
        filas.append(f"{clave}   {c}")
        partes.append(f"{clave}:{huella(salt, c)}")
        blobs[clave] = cifrar(salt, c, url)
        y, m = siguiente_mes(y, m)
    bloque = "const val MONTHLY_HASHES =\n" + " +\n".join(f'        "{p};"' for p in partes)
    cfg = re.sub(r"const val MONTHLY_HASHES =\s*(?:\"[^\"]*\"\s*\+?\s*)+", lambda _: bloque + "\n", cfg)
    CONFIG.write_text(escribir_blobs(cfg, blobs), encoding="utf-8")
    Path("codigos_mensuales.txt").write_text(
        "CÓDIGOS MENSUALES (privado, no subir a GitHub)\nMes       Código\n" + "\n".join(filas) + "\n", encoding="utf-8")
    print("\n".join(filas))
    print("\nConfig.kt actualizado y codigos_mensuales.txt guardado.")


def cambiar_url(args):
    codigo = opcion(args, "--permanente")
    if not codigo or not args:
        sys.exit("Uso: python3 tools/generar_codigo.py url DIRECCION --permanente CODIGO")
    url = args[0]
    cfg = leer_config()
    salt = salt_actual(cfg)
    if not re.search(re.escape(huella(salt, codigo)), cfg):
        sys.exit("Ese no es el código permanente actual.")
    blobs = {"perm": cifrar(salt, codigo, url)}
    archivo = Path("codigos_mensuales.txt")
    if archivo.exists():
        for mes, c in re.findall(r"^(\d{4}-\d{2})\s+(\S+)", archivo.read_text(encoding="utf-8"), re.M):
            blobs[mes] = cifrar(salt, c, url)
    else:
        print("Aviso: no encuentro codigos_mensuales.txt en esta carpeta; solo se cifró para el código permanente.")
    CONFIG.write_text(escribir_blobs(cfg, blobs), encoding="utf-8")
    print(f"Dirección cifrada para {len(blobs)} códigos. Config.kt actualizado.")


if __name__ == "__main__":
    cmds = {"permanente": permanente, "meses": meses, "url": cambiar_url}
    if len(sys.argv) < 2 or sys.argv[1] not in cmds:
        print(__doc__)
        sys.exit(1)
    cmds[sys.argv[1]](sys.argv[2:])
