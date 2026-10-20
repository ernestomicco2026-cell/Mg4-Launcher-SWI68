#!/usr/bin/env python3
"""Crea i pacchetti dei loghi radio per paese (loghi/<CC>.zip + loghi/indice.json).

Ingresso: una cartella con una sottocartella (o uno zip) per paese, chiamata "CC - Nome" (es. "IT - Italia"),
con i PNG e un indice.csv (colonne file, nome, uuid, stream, homepage, generi, logo_originale), come quelle
scaricate da Radio Browser.

Per ogni paese:
  1. pulisce l'elenco: file vuoti o rotti, doppioni (stesso nome normalizzato o stessa immagine), varianti tecniche
     (Mobile, 48kbps, AAC, HQ...) e sottocanali tematici quando c'è la stazione principale;
  2. riduce ogni logo a 256x256 al massimo (proporzioni e trasparenza mantenute) in WebP (Android 9 lo legge);
  3. rinomina con il nome normalizzato del launcher (minuscole, solo lettere e cifre: "RTL 102.5" -> rtl1025);
  4. scrive alias.txt (nomi alternativi e nomi RDS brevi -> file);
  5. crea loghi/<CC>.zip e aggiorna loghi/indice.json (nome del paese nelle lingue del launcher, versione, dimensione,
     SHA-256, numero di loghi).
Alla fine stampa il riepilogo (loghi e peso prima e dopo) e le principali stazioni FM nazionali che mancano.

Uso: python3 tools/loghi_pacchetti.py <cartella_ingresso> <cartella_uscita> [--paesi IT,PL] [--versione 1]
     [--alias-extra tools/loghi_alias_extra.txt] [--anteprima cartella]
Serve Pillow (e Babel per i nomi dei paesi): pip3 install pillow babel
"""
import argparse
import csv
import hashlib
import io
import json
import os
import re
import sys
import unicodedata
import zipfile

from PIL import Image

LANGS = ["it", "en", "es", "de", "pt", "fr", "tr", "pl"]
MAX = 256
WEBP_QUALITY = 82

# ---------------------------------------------------------------------------------------------------------------
# nomi


def norm(s):
    """Nome normalizzato del launcher (StationLogos.norm): senza accenti, minuscolo, solo lettere e cifre."""
    s = unicodedata.normalize("NFD", s or "")
    s = "".join(ch for ch in s if unicodedata.category(ch) != "Mn")
    s = s.replace("ł", "l").replace("Ł", "L").replace("ø", "o").replace("Ø", "O").replace("ß", "ss").replace("ı", "i")
    return re.sub(r"[^a-z0-9]", "", s.lower())


# varianti tecniche dello stesso flusso: si tolgono se esiste la stazione principale
TECH = re.compile(r"(\[[^\]]*\]|\((?:[^)]*kbps|[^)]*aac|[^)]*mp3|[^)]*hq|[^)]*mobile|[^)]*low|[^)]*high)[^)]*\)|"
                  r"\b(mobile|\d+\s*k(?:bps|b)?|aac\+?|he-?aac|mp3|ogg|opus|hq|hd|lq|low|high quality|hi-?fi|"
                  r"stream|backup|alt(?:ernative)?|ssl|https?|\+1|test)\b)", re.I)
# prefissi di gruppo che Radio Browser mette davanti al nome (GEDI - Radio Capital)
PREFIX = re.compile(r"^(gedi|rai|mediaset|rtl group|radiomediaset|ens)\s*[-–:|]\s*", re.I)
# parole troppo generiche per essere una "stazione principale" di cui togliere i sottocanali
GENERIC = {"radio", "fm", "webradio", "radioweb", "music", "hits", "the", "radiofm", "net", "online", "tv"}


def find_key(name, present, alias=None):
    """File del pacchetto per un nome: uguale, con o senza "radio" davanti/dietro, oppure tramite gli alias."""
    k = norm(name)
    alias = alias or {}
    for c in (k, "radio" + k, k[5:] if k.startswith("radio") else None, k + "radio", k + "fm"):
        if c and c in present:
            return c
        if c and c in alias:
            return alias[c]
    return None


def clean_name(n):
    n = (n or "").strip()
    n = re.sub(r"^[#_*'\s]+", "", n)
    n = PREFIX.sub("", n)
    n = re.split(r"\s+[|]\s+", n)[0]
    return n.strip()


def base_name(n):
    """Nome senza le parti tecniche (bitrate, Mobile, AAC...)."""
    b = TECH.sub(" ", n)
    b = re.sub(r"[-–,;:|]+\s*$", "", re.sub(r"\s+", " ", b)).strip()
    return b


# ---------------------------------------------------------------------------------------------------------------
# immagini


def load(data):
    im = Image.open(io.BytesIO(data))
    im.load()
    if im.width < 16 or im.height < 16:
        raise ValueError("troppo piccola")
    return im.convert("RGBA")


def ahash(im, size=12):
    """Impronta dell'immagine (media su 12x12, colori e trasparenza): stessa impronta = stesso logo."""
    bg = Image.new("RGBA", im.size, (255, 255, 255, 255))
    bg.alpha_composite(im)
    small = bg.convert("L").resize((size, size), Image.BILINEAR)
    px = list(small.tobytes())
    avg = sum(px) / len(px)
    bits = "".join("1" if p > avg else "0" for p in px)
    return "%036x" % int(bits, 2)


def shrink(im):
    """Taglia i bordi trasparenti, riduce a 256 px al massimo e codifica in WebP con trasparenza."""
    bbox = im.getchannel("A").getbbox()
    if bbox:
        im = im.crop(bbox)
    if max(im.size) > MAX:
        k = MAX / max(im.size)
        im = im.resize((max(1, round(im.width * k)), max(1, round(im.height * k))), Image.LANCZOS)
    out = io.BytesIO()
    im.save(out, "WEBP", quality=WEBP_QUALITY, method=6)
    return out.getvalue(), im.size


# ---------------------------------------------------------------------------------------------------------------
# sorgenti (cartelle o zip)


class Source:
    """Una cartella "CC - Nome" oppure lo zip che la contiene."""

    def __init__(self, path):
        self.path = path
        self.zip = zipfile.ZipFile(path) if path.endswith(".zip") else None
        if self.zip:
            names = [n for n in self.zip.namelist() if n.endswith("indice.csv")]
            if not names:
                raise ValueError("indice.csv mancante in " + path)
            self.prefix = names[0][: -len("indice.csv")]
            folder = self.prefix.rstrip("/").split("/")[-1]
        else:
            self.prefix = ""
            folder = os.path.basename(path.rstrip("/"))
        self.cc = folder[:2].upper()
        self.label = folder[5:] if len(folder) > 5 else folder

    def read(self, name):
        if self.zip:
            try:
                return self.zip.read(self.prefix + name)
            except KeyError:
                return None
        p = os.path.join(self.path, name)
        return open(p, "rb").read() if os.path.exists(p) else None

    def rows(self):
        data = self.read("indice.csv")
        return list(csv.DictReader(io.StringIO(data.decode("utf-8-sig"))))


# ---------------------------------------------------------------------------------------------------------------


def build_country(src, extra_alias, preview=None):
    rows = src.rows()
    before_n, before_bytes = 0, 0
    dropped = {"rotti": [], "varianti": [], "sottocanali": [], "doppioni": []}
    items = []
    for r in rows:
        data = src.read(r["file"])
        if data:
            before_n += 1
            before_bytes += len(data)
        name = clean_name(r.get("nome") or r["file"].rsplit("_", 1)[0])
        if not data:
            dropped["rotti"].append(name + " (file mancante)")
            continue
        try:
            im = load(data)
        except Exception as e:
            dropped["rotti"].append("%s (%s)" % (name, e))
            continue
        items.append({"name": name, "base": base_name(name), "img": im, "hash": ahash(im), "orig": r.get("nome", "")})

    # 1) varianti tecniche: stesso nome base di un'altra voce senza parti tecniche
    plain = {norm(i["name"]) for i in items if i["base"] == i["name"]}
    keep = []
    for i in items:
        if i["base"] != i["name"] and norm(i["base"]) in plain:
            dropped["varianti"].append(i["name"])
        else:
            if i["base"] != i["name"]:
                i["name"] = i["base"]  # solo la variante: tiene il nome pulito
            keep.append(i)
    items = keep

    # 2) doppioni per nome normalizzato: tiene l'immagine più grande, gli altri nomi diventano alias
    byname = {}
    for i in items:
        k = norm(i["name"])
        if not k:
            dropped["rotti"].append(i["name"] + " (nome vuoto)")
            continue
        cur = byname.get(k)
        if cur is None:
            byname[k] = i
            i["aliases"] = set()
        else:
            dropped["doppioni"].append(i["name"])
            if i["img"].width * i["img"].height > cur["img"].width * cur["img"].height:
                i["aliases"] = cur["aliases"]
                byname[k] = i
    items = list(byname.values())

    # 3) sottocanali tematici: nome che inizia con il nome di un'altra stazione (es. "Virgin Radio Hard Rock")
    keys = {norm(i["name"]) for i in items}
    mains = sorted((k for k in keys if len(k) >= 4 and k not in GENERIC), key=len, reverse=True)
    keep = []
    for i in items:
        k = norm(i["name"])
        parent = next((m for m in mains if m != k and k.startswith(m)), None)
        if parent:
            dropped["sottocanali"].append(i["name"] + " → " + parent)
        else:
            keep.append(i)
    items = keep

    # 4) doppioni per immagine: stesso logo con nomi diversi → un file, gli altri nomi alias
    byhash = {}
    for i in items:
        h = i["hash"]
        if h in byhash:
            main = byhash[h]
            if len(norm(i["name"])) < len(norm(main["name"])):
                i["aliases"] |= main["aliases"] | {main["name"]}
                byhash[h] = i
            else:
                main["aliases"] |= i["aliases"] | {i["name"]}
            dropped["doppioni"].append(i["name"] + " (stessa immagine)")
        else:
            byhash[h] = i
    items = sorted(byhash.values(), key=lambda x: norm(x["name"]))

    # 5) riduzione e alias
    files = {}
    alias = {}
    for i in items:
        data, size = shrink(i["img"])
        key = norm(i["name"])
        files[key + ".webp"] = data
        for a in i["aliases"]:
            if norm(a) and norm(a) != key:
                alias[norm(a)] = key
        if preview is not None:
            preview[key] = data
    present = set(k[:-5] for k in files)
    for a, target in extra_alias.get(src.cc, {}).items():
        t = find_key(target, present, alias)
        if t and norm(a) and norm(a) != t:
            alias[norm(a)] = t
    return files, alias, dropped, before_n, before_bytes


def country_names(cc, fallback):
    names = {}
    try:
        from babel import Locale
        for lang in LANGS:
            n = Locale.parse(lang).territories.get(cc)
            names[lang] = n[0].upper() + n[1:] if n else fallback
    except Exception:
        names = {lang: fallback for lang in LANGS}
    return names


def read_extra_alias(path):
    """File "CC | nome trasmesso = file" (righe vuote e # ignorate)."""
    out = {}
    if not path or not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#") or "|" not in line or "=" not in line:
            continue
        cc, rest = line.split("|", 1)
        a, t = rest.split("=", 1)
        out.setdefault(cc.strip().upper(), {})[a.strip()] = t.strip()
    return out


def read_major(path):
    """File "CC | nome stazione" delle principali stazioni FM nazionali (per l'elenco di quelle mancanti)."""
    out = {}
    if not path or not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#") or "|" not in line:
            continue
        cc, n = line.split("|", 1)
        out.setdefault(cc.strip().upper(), []).append(n.strip())
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ingresso")
    ap.add_argument("uscita")
    ap.add_argument("--paesi", default="")
    ap.add_argument("--versione", type=int, default=1)
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--alias-extra", default=os.path.join(here, "loghi_alias_extra.txt"))
    ap.add_argument("--principali", default=os.path.join(here, "loghi_principali.txt"))
    ap.add_argument("--anteprima", default="")
    ap.add_argument("--sovrascrivi", action="store_true", help="rifà anche i paesi già presenti nella cartella di uscita")
    args = ap.parse_args()

    wanted = {p.strip().upper() for p in args.paesi.split(",") if p.strip()}
    extra = read_extra_alias(args.alias_extra)
    major = read_major(args.principali)
    os.makedirs(args.uscita, exist_ok=True)
    idx_path = os.path.join(args.uscita, "indice.json")
    index = json.load(open(idx_path)) if os.path.exists(idx_path) else {"versione": args.versione, "paesi": []}
    index["versione"] = args.versione
    report = []

    entries = sorted(os.listdir(args.ingresso))
    for e in entries:
        p = os.path.join(args.ingresso, e)
        if not (os.path.isdir(p) or e.endswith(".zip")) or e.startswith("."):
            continue
        try:
            src = Source(p)
        except Exception as ex:
            print("salto", e, ex)
            continue
        if wanted and src.cc not in wanted:
            continue
        if os.path.exists(os.path.join(args.uscita, src.cc + ".zip")) and not args.sovrascrivi:
            print("salto", src.cc, "(pacchetto già presente; --sovrascrivi per rifarlo)")
            continue
        preview = {} if args.anteprima else None
        files, alias, dropped, bn, bb = build_country(src, extra, preview)
        if not files:
            report.append((src.cc, src.label, bn, bb, 0, 0, dropped, []))
            continue
        alias_txt = "# Loghi radio %s (%s), pacchetto %d\n# nome trasmesso = file (senza estensione)\n" % (src.cc, src.label, args.versione)
        alias_txt += "".join("%s = %s\n" % (a, t) for a, t in sorted(alias.items()))
        zpath = os.path.join(args.uscita, src.cc + ".zip")
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_STORED) as z:   # WebP è già compresso
            for n, d in sorted(files.items()):
                z.writestr(n, d)
            z.writestr("alias.txt", alias_txt, compress_type=zipfile.ZIP_DEFLATED)
        size = os.path.getsize(zpath)
        sha = hashlib.sha256(open(zpath, "rb").read()).hexdigest()
        index["paesi"] = [x for x in index["paesi"] if x["codice"] != src.cc] + [{
            "codice": src.cc, "nomi": country_names(src.cc, src.label), "file": src.cc + ".zip",
            "versione": args.versione, "dimensione": size, "sha256": sha, "loghi": len(files), "alias": len(alias)}]
        present = {k[:-5] for k in files}
        missing = [m for m in major.get(src.cc, []) if not find_key(m, present, alias)]
        report.append((src.cc, src.label, bn, bb, len(files), size, dropped, missing))
        if preview is not None:
            os.makedirs(args.anteprima, exist_ok=True)
            for k, d in preview.items():
                open(os.path.join(args.anteprima, src.cc + "_" + k + ".webp"), "wb").write(d)
        print("%s %-24s %4d → %4d loghi   %6.1f MB → %5.2f MB" % (src.cc, src.label, bn, len(files), bb / 1e6, size / 1e6))
        sys.stdout.flush()

    index["paesi"].sort(key=lambda x: x["codice"])
    json.dump(index, open(idx_path, "w"), ensure_ascii=False, indent=1)
    rpath = os.path.join(args.uscita, "riepilogo.md")
    with open(rpath, "w", encoding="utf-8") as f:
        f.write("# Pacchetti loghi radio (versione %d)\n\n| Paese | Loghi prima | MB prima | Loghi dopo | MB dopo | Tolti |\n|---|---:|---:|---:|---:|---|\n" % args.versione)
        for cc, label, bn, bb, an, ab, dr, _ in report:
            f.write("| %s %s | %d | %.1f | %d | %.2f | %s |\n" % (cc, label, bn, bb / 1e6, an, ab / 1e6,
                    ", ".join("%s %d" % (k, len(v)) for k, v in dr.items() if v)))
        f.write("\n## Stazioni FM nazionali che mancano\n\n")
        for cc, label, *_, missing in report:
            if missing:
                f.write("- **%s %s**: %s\n" % (cc, label, ", ".join(missing)))
        f.write("\n## Dettaglio di cosa è stato tolto\n")
        for cc, label, bn, bb, an, ab, dr, _ in report:
            f.write("\n### %s %s\n" % (cc, label))
            for k, v in dr.items():
                if v:
                    f.write("- %s: %s\n" % (k, "; ".join(v)))
    print("riepilogo:", rpath)


if __name__ == "__main__":
    main()
