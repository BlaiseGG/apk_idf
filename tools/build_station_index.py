#!/usr/bin/env python3
"""Construit l'index des gares/lignes IDFM embarqué dans l'APK (assets/stations.json).

Sources : données ouvertes Île-de-France Mobilités (aucune clé, aucun quota PRIM).
  arrets-lignes            : ligne -> arrêt (quai, "ArR")
  arrets                   : ArR -> zone d'arrêt (ZdA) + coordonnées
  zones-d-arrets           : ZdA -> zone de correspondance (ZdC = stop_area Navitia)
  zones-de-correspondance  : nom et commune de la ZdC
  referentiel-des-lignes   : nom, couleurs, réseau des lignes

Format de sortie (compact) :
  {"v":1, "lines":[[id, code, nom, catégorie, libellé_mode, couleur, couleur_texte]],
   "stations":[[zdc, nom, lat, lon, [[idx_ligne, [arr...], [zda...]], ...]]]}
"""
import csv, gzip, io, json, re, sys, urllib.request
from collections import defaultdict

BASE = "https://data.iledefrance-mobilites.fr/api/explore/v2.1/catalog/datasets/{}/exports/csv?delimiter=%3B"


def load(name):
    req = urllib.request.Request(BASE.format(name), headers={"Accept-Encoding": "identity"})
    with urllib.request.urlopen(req, timeout=300) as r:
        raw = r.read()
    if raw[:2] == b"\x1f\x8b":  # réponse compressée (gzip)
        raw = gzip.decompress(raw)
    text = raw.decode("utf-8-sig")
    return list(csv.DictReader(io.StringIO(text), delimiter=";"))


def last_int(s):
    m = re.search(r"(\d+)\D*$", s or "")
    return int(m.group(1)) if m else None


def category(mode, ref):
    m = (mode or "").lower()
    sub = (ref.get("transportsubmode") or "").lower()
    net = (ref.get("networkname") or "").lower()
    name = (ref.get("name_line") or "").lower()
    if m == "rapidtransit" or "rer" in net or name.startswith("rer"):
        return "RER"
    if m == "metro":
        return "METRO"
    if m in ("tramway", "tram"):
        return "TRAM"
    if m in ("localtrain", "train", "railshuttle", "rail", "regionalrail") or "train" in sub or "rail" in sub:
        return "TRAIN"
    if m in ("bus", "coach") or "bus" in sub:
        return "BUS"
    return "OTHER"


def mode_label(cat, ref, mode):
    net = (ref.get("networkname") or "").strip()
    return {
        "RER": "RER", "METRO": "Métro", "TRAM": "Tramway",
        "TRAIN": net or "Train", "BUS": ("Bus " + net).strip(),
    }.get(cat, mode or "Autre")


def town(t):
    t = (t or "").strip()
    return "Paris" if t.startswith("Paris") else t


def main(out):
    al = load("arrets-lignes")
    arrets = load("arrets")
    zdas = load("zones-d-arrets")
    zdcs = load("zones-de-correspondance")
    refs = load("referentiel-des-lignes")
    print(f"arrets-lignes={len(al)} arrets={len(arrets)} zda={len(zdas)} zdc={len(zdcs)} lignes={len(refs)}")
    print("modes:", sorted({r["mode"] for r in al}))

    arr_zda, arr_coord = {}, {}
    for a in arrets:
        arr, zda = last_int(a["arrid"]), last_int(a["zdaid"])
        if arr is None or zda is None:
            continue
        arr_zda[arr] = zda
        try:
            lat, lon = (float(x) for x in a["arrgeopoint"].split(","))
            arr_coord[arr] = (lat, lon)
        except Exception:
            pass
    zda_zdc = {last_int(z["zdaid"]): last_int(z["zdcid"]) for z in zdas if z["zdaid"] and z["zdcid"]}
    zdc_info = {last_int(z["zdcid"]): (z["zdcname"].strip(), town(z["zdctown"])) for z in zdcs if z["zdcid"]}
    ref_by_id = {r["id_line"]: r for r in refs}

    lines, line_idx = [], {}
    # zdc -> idx ligne -> (set arr, set zda)
    st = defaultdict(lambda: defaultdict(lambda: (set(), set())))
    coords = defaultdict(list)
    missing = 0
    for r in al:
        code = (r["id"] or "").split(":")[-1]
        sid = last_int(r["stop_id"])
        if not code or sid is None:
            continue
        if sid in arr_zda:
            arr, zda = sid, arr_zda[sid]
        elif sid in zda_zdc:
            arr, zda = None, sid
        else:
            missing += 1
            continue
        zdc = zda_zdc.get(zda)
        if zdc is None:
            missing += 1
            continue
        if code not in line_idx:
            ref = ref_by_id.get(code, {})
            cat = category(r["mode"], ref)
            short = (ref.get("shortname_line") or r["shortname"] or r["route_long_name"]).strip()
            name = (ref.get("name_line") or r["route_long_name"]).strip()
            if cat == "RER" and not name.lower().startswith("rer"):
                name = "RER " + short
            elif cat == "METRO" and not name.lower().startswith("m"):
                name = "Métro " + short
            elif cat == "TRAM" and not name.upper().startswith("T"):
                name = "Tram " + short
            color = (ref.get("colourweb_hexa") or "5a5a5a").strip()[:6]
            text = (ref.get("textcolourweb_hexa") or "ffffff").strip()[:6]
            line_idx[code] = len(lines)
            lines.append([code, short, name, cat, mode_label(cat, ref, r["mode"]), color, text])
        i = line_idx[code]
        arrs, zset = st[zdc][i]
        if arr is not None:
            arrs.add(arr)
            if arr in arr_coord:
                coords[zdc].append(arr_coord[arr])
        zset.add(zda)
        if arr is None:
            try:
                coords[zdc].append((float(r["stop_lat"]), float(r["stop_lon"])))
            except Exception:
                pass

    cat_order = {"RER": 0, "METRO": 1, "TRAIN": 2, "TRAM": 3, "OTHER": 4, "BUS": 5}

    # Doublons (même RER/métro/tram sous deux identifiants) : on garde la ligne qui dessert
    # le plus de gares (la ligne principale), en fusionnant ses quais.
    served = defaultdict(int)
    for by_line in st.values():
        for i in by_line:
            served[i] += 1
    for zdc, by_line in st.items():
        groups = defaultdict(list)
        for i in list(by_line):
            code, short, name, cat = lines[i][:4]
            if cat in ("RER", "METRO", "TRAM"):
                groups[(cat, short.upper())].append(i)
        for ids in groups.values():
            if len(ids) < 2:
                continue
            keep = max(ids, key=lambda i: served[i])
            for i in ids:
                if i != keep:
                    a2, z2 = by_line.pop(i)
                    by_line[keep][0].update(a2)
                    by_line[keep][1].update(z2)
                    print("   doublon fusionné :", lines[i][0], "->", lines[keep][0], lines[keep][2])
    stations = []
    for zdc, by_line in st.items():
        name, t = zdc_info.get(zdc, ("", ""))
        if not name or not coords[zdc]:
            continue
        lat = sum(c[0] for c in coords[zdc]) / len(coords[zdc])
        lon = sum(c[1] for c in coords[zdc]) / len(coords[zdc])
        label = f"{name} ({t})" if t else name
        ls = sorted(by_line.items(), key=lambda kv: (cat_order[lines[kv[0]][3]], lines[kv[0]][1].zfill(4)))
        stations.append([zdc, label, round(lat, 6), round(lon, 6),
                         [[i, sorted(a), sorted(z)] for i, (a, z) in ls]])

    with open(out, "w", encoding="utf-8") as f:
        json.dump({"v": 1, "lines": lines, "stations": stations}, f, ensure_ascii=False, separators=(",", ":"))
    print(f"OK : {len(stations)} gares/arrêts, {len(lines)} lignes, {missing} associations ignorées -> {out}")
    for probe in (71264, 71410, 60797, 474151, 73794):
        s = next((x for x in stations if x[0] == probe), None)
        if s:
            print("  ", s[1], "->", ", ".join(f"{lines[i][2]}[{lines[i][0]}]" for i, _, _ in s[4] if lines[i][3] != "BUS"))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "stations.json")
