#!/usr/bin/env python3
"""Controllo traduzioni (1.0.5): ogni chiave di values/strings.xml deve esistere in tutte le lingue, e nelle lingue
diverse dall'inglese non devono restare testi identici a quelli inglesi (tranne nomi propri e sigle).
Uso: python3 tools/check_i18n.py   (esce con 1 se trova problemi)"""
import re, sys, xml.etree.ElementTree as ET

LANGS = ['it', 'es', 'de', 'pt', 'fr', 'tr', 'pl']
# testi che restano uguali in tutte le lingue: nomi propri, sigle, unità, simboli
SAME_OK = re.compile(r"^(CarPlay|Android Auto|One Pedal Drive|MG4 Launcher|MG4 Store|MG4 Clima|MG4 360|MG|USB|Bluetooth|FM|DAB\+?|AM|"
                     r"OK|A/C|ACC|LKA|OPD|TJA|V2L|BMS|km|mi|bar|psi|kPa|°C|°F|360°?|Wi-?Fi|GPS|SOS|Online|Radio|Media|Auto|"
                     r"Spotify|Amazon Music|Telegram|GitHub|Dongle|Log|Info|Test|Video|Start|Stop|Standard|Normal|Max|Min|"
                     r"CarPlay / Android Auto|One Pedal Drive \(OPD\)|18″ .*|[\d\s%$a-z.:·/+-]{0,6}|%1\$d h %2\$d min|%1\$d min)$")
# parole uguali all'inglese ma giuste anche nella lingua (controllate a mano)
SAME_WORD = {
    'it': {'sec_home', 'app_account', 'group_comfort'},
    'es': {'apps_title', 'app_manual', 'upd_error_code'},
    'de': {'sec_system', 'set_updates', 'apps_title', 'app_navigator', 'btn_logo_code', 'cam360_original', 'anim_sim_label'},
    'pt': {'apps_title', 'app_manual'},
    'fr': {'app_navigator', 'logos_pack_state', 'tile_acc', 'btn_logo_code', 'anim_sim_label'},
    'tr': {'app_usb_video'},
    'pl': {'sec_system'},
}


def load(path):
    out = {}
    for e in ET.parse(path).getroot():
        if e.tag == 'string':
            out[e.get('name')] = ''.join(e.itertext())
    return out


base = load('app/res/values/strings.xml')
problems = 0
for lang in LANGS:
    tr = load(f'app/res/values-{lang}/strings.xml')
    missing = [k for k in base if k not in tr]
    for k in missing:
        print(f'{lang}: manca {k}')
    problems += len(missing)
    same = [k for k in base if k in tr and tr[k] == base[k] and not SAME_OK.match(base[k].strip())
            and re.search(r'[A-Za-z]{3,}', base[k]) and k not in SAME_WORD.get(lang, ())]
    for k in same:
        print(f'{lang}: uguale all\'inglese {k} = {base[k][:70]!r}')
    problems += len(same)
print(f'{len(base)} chiavi, {problems} problemi')
sys.exit(1 if problems else 0)
