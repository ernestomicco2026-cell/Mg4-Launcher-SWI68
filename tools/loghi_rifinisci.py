#!/usr/bin/env python3
"""Rifinisce i pacchetti dei loghi già pronti (loghi/<CC>.zip), senza i loghi originali:

  - nomi con il paese in fondo ("Virgin Radio Italy"): aggiunge l'alias senza paese (virginradio) e toglie i
    sottocanali di quella stazione ("Virgin Radio Hard Rock", "Virgin Radio Rock 80"...);
  - alias delle stazioni principali e RDS il cui logo ha un nome più lungo ("Radio 24" -> radio24ilsole24ore): si usa
    il logo che inizia con quel nome (il più corto), solo per nomi di almeno 6 caratteri;
  - aggiorna indice.json (dimensione e SHA-256) e la sezione "Stazioni FM nazionali che mancano" di riepilogo.md.

Uso: python3 tools/loghi_rifinisci.py loghi
"""
import hashlib
import io
import json
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from loghi_pacchetti import norm, find_key, read_extra_alias, read_major, GENERIC  # noqa: E402

# parole del paese del pacchetto in fondo al nome (solo quelle del paese stesso: "Hotmix Spain" in Francia è un sottocanale)
COUNTRY_WORDS = {"IT": ["italy", "italia"], "GB": ["uk", "england"], "DE": ["deutschland", "germany"], "FR": ["france"],
                 "ES": ["espana", "spain"], "PL": ["polska", "poland"], "NL": ["nederland", "holland"],
                 "BE": ["belgie", "belgique", "belgium"], "CH": ["schweiz", "suisse", "svizzera", "switzerland"],
                 "AT": ["osterreich", "austria"], "PT": ["portugal"], "TR": ["turkiye", "turkey"], "IE": ["ireland"],
                 "SE": ["sverige"], "NO": ["norge"], "DK": ["danmark"], "FI": ["suomi"], "RO": ["romania"],
                 "GR": ["hellas", "greece"], "CZ": ["ceska"], "SK": ["slovensko"], "HR": ["hrvatska"], "HU": ["magyarorszag"]}


def prefix_key(name, present):
    k = norm(name)
    if len(k) < 6:
        return None
    c = sorted((p for p in present if p.startswith(k)), key=len)
    return c[0] if c else None


def refine(path, extra, major):
    z = zipfile.ZipFile(path)
    files = {n: z.read(n) for n in z.namelist() if n != "alias.txt"}
    alias_lines = z.read("alias.txt").decode("utf-8").splitlines()
    head = [l for l in alias_lines if l.startswith("#")]
    alias = {}
    for l in alias_lines:
        if "=" in l and not l.startswith("#"):
            a, t = l.split("=", 1)
            alias[a.strip()] = t.strip()
    present = {n.rsplit(".", 1)[0]: n for n in files}
    cc = os.path.basename(path)[:2].upper()
    removed = []

    # 1) nome con il paese in fondo: alias senza paese e sottocanali di quella stazione
    for k in sorted(present, key=len):
        if k not in present:
            continue
        for w in COUNTRY_WORDS.get(cc, []):
            if k.endswith(w) and len(k) - len(w) >= 4:
                base = k[: -len(w)]
                if base in GENERIC or base in present:
                    break
                alias.setdefault(base, k)
                for other in list(present):
                    if other != k and other.startswith(base) and other not in alias.values():
                        files.pop(present.pop(other))
                        removed.append(other + " → " + k)
                        for a, t in list(alias.items()):
                            if t == other:
                                alias[a] = k
                break

    # 2) alias RDS e stazioni principali verso un logo con il nome più lungo
    keys = set(present)
    for a, target in extra.get(cc, {}).items():
        if norm(a) in alias or norm(a) in keys:
            continue
        t = find_key(target, keys, alias) or prefix_key(target, keys)
        if t and norm(a) != t:
            alias[norm(a)] = t
    for m in major.get(cc, []):
        if not find_key(m, keys, alias):
            t = prefix_key(m, keys)
            if t:
                alias[norm(m)] = t
    alias = {a: t for a, t in alias.items() if t in keys and a != t}

    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as nz:
        for n in sorted(files):
            nz.writestr(n, files[n])
        nz.writestr("alias.txt", "\n".join(head) + "\n" + "".join("%s = %s\n" % x for x in sorted(alias.items())),
                    compress_type=zipfile.ZIP_DEFLATED)
    data = out.getvalue()
    open(path, "wb").write(data)
    missing = [m for m in major.get(cc, []) if not find_key(m, keys, alias)]
    return len(files), len(alias), len(data), hashlib.sha256(data).hexdigest(), removed, missing


def main():
    d = sys.argv[1]
    here = os.path.dirname(os.path.abspath(__file__))
    extra = read_extra_alias(os.path.join(here, "loghi_alias_extra.txt"))
    major = read_major(os.path.join(here, "loghi_principali.txt"))
    idx_path = os.path.join(d, "indice.json")
    idx = json.load(open(idx_path))
    miss_lines, rem_lines = [], []
    for p in idx["paesi"]:
        n, na, size, sha, removed, missing = refine(os.path.join(d, p["file"]), extra, major)
        if n != p["loghi"]:
            print(p["codice"], p["loghi"], "→", n, "loghi")
        p.update({"loghi": n, "alias": na, "dimensione": size, "sha256": sha})
        if missing:
            miss_lines.append("- **%s %s**: %s" % (p["codice"], p["nomi"].get("it", ""), ", ".join(missing)))
        if removed:
            rem_lines.append("- **%s**: %s" % (p["codice"], "; ".join(removed)))
    json.dump(idx, open(idx_path, "w"), ensure_ascii=False, indent=1)
    rp = os.path.join(d, "riepilogo.md")
    if os.path.exists(rp):
        s = open(rp, encoding="utf-8").read()
        a = s.index("## Stazioni FM nazionali che mancano")
        b = s.index("## Dettaglio di cosa è stato tolto")
        s = s[:a] + "## Stazioni FM nazionali che mancano\n\n" + "\n".join(miss_lines) + "\n\n" + s[b:]
        if rem_lines:
            s += "\n## Sottocanali tolti dopo (stazione con il paese nel nome)\n\n" + "\n".join(rem_lines) + "\n"
        # tabella: loghi e MB dopo aggiornati
        rows = {p["codice"]: p for p in idx["paesi"]}

        def fix(m):
            cc = m.group(1)
            p = rows.get(cc)
            if not p:
                return m.group(0)
            return "| %s%s | %s | %s | %d | %.2f |" % (cc, m.group(2), m.group(3), m.group(4), p["loghi"], p["dimensione"] / 1e6)
        s = re.sub(r"^\| ([A-Z]{2})( [^|]*)\| (\d+) \| ([\d.]+) \| \d+ \| [\d.]+ \|", fix, s, flags=re.M)
        open(rp, "w", encoding="utf-8").write(s)
    print("fatto")


if __name__ == "__main__":
    main()
