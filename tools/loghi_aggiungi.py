#!/usr/bin/env python3
"""Aggiunge ai pacchetti loghi/<CC>.zip i loghi nuovi da zip "CC - Nome.zip" (PNG con il nome della stazione).

Ogni logo viene ridotto come gli altri (WebP, max 256 px, trasparenza) e salvato con il nome normalizzato; se c'era
già un logo con quel nome viene sostituito. Poi si lancia loghi_rifinisci.py per alias, indice.json e riepilogo.md.
Uso: python3 tools/loghi_aggiungi.py loghi-nuovi loghi
"""
import io
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from loghi_pacchetti import norm, load, shrink  # noqa: E402


def main():
    src, dst = sys.argv[1], sys.argv[2]
    for f in sorted(os.listdir(src)):
        if not f.lower().endswith(".zip"):
            continue
        cc = f[:2].upper()
        pack = os.path.join(dst, cc + ".zip")
        if not os.path.exists(pack):
            print("manca il pacchetto", cc)
            continue
        new = {}
        with zipfile.ZipFile(os.path.join(src, f)) as z:
            for n in z.namelist():
                base = os.path.basename(n)
                if n.startswith("__MACOSX") or base.startswith(".") or not base.lower().endswith((".png", ".jpg", ".jpeg", ".webp")):
                    continue
                key = norm(base.rsplit(".", 1)[0])
                new[key + ".webp"] = shrink(load(z.read(n)))[0]
        if not new:
            continue
        with zipfile.ZipFile(pack) as z:
            files = {n: z.read(n) for n in z.namelist()}
        files.update(new)
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
            for n in sorted(files):
                z.writestr(n, files[n], compress_type=zipfile.ZIP_DEFLATED if n == "alias.txt" else zipfile.ZIP_STORED)
        open(pack, "wb").write(out.getvalue())
        print(cc, "+", ", ".join(sorted(k[:-5] for k in new)))


if __name__ == "__main__":
    main()
