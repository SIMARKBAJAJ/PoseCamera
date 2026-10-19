"""USDA FDC (SR Legacy + Foundation CSV dirs) -> importer JSON. Stdlib only.

Usage: python -I usda_foods.py <sr_dir> <foundation_dir> <out.json>
"""
import csv
import json
import re
import sys
from collections import Counter

SR_T = "USDA FoodData Central SR Legacy (2018-04)"
FF_T = "USDA FoodData Central Foundation Foods (2026-04-30)"
NOTE = "CC0 public domain; nutrient database, not a research citation"  # <=64 chars (String(64))
# nutrient ids -> field. kcal handled separately (1008, then Atwater 2047, 2048).
NUT = {1003: "protein_g", 1004: "fat_g", 1005: "carbs_g", 1079: "fiber_g", 2000: "sugar_g",
       1093: "sodium_mg", 1089: "iron_mg", 1087: "calcium_mg", 1178: "vitamin_b12_ug"}
KCAL = (1008, 2047, 2048)
WANT = set(NUT) | set(KCAL)


def rows(d, name):
    with open(f"{d}/{name}", encoding="utf-8", newline="") as f:
        yield from csv.DictReader(f)


def clip(s, n):
    """s cut to n chars at a word boundary where possible (no ellipsis)."""
    if len(s) <= n:
        return s
    head = s[: n + 1]
    return (head.rsplit(" ", 1)[0] if " " in head else s[:n]).rstrip(" ,;:-(/")


def slugify(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def load(d, dtype, prefix, stats):
    cats = {r["id"]: r["description"] for r in rows(d, "food_category.csv")}
    units = {r["id"]: r["name"] for r in rows(d, "measure_unit.csv")}
    foods = {r["fdc_id"]: r for r in rows(d, "food.csv") if r["data_type"] == dtype}
    nut = {}
    for r in rows(d, "food_nutrient.csv"):
        n = int(r["nutrient_id"])
        if r["fdc_id"] in foods and n in WANT and r["amount"] != "":
            nut.setdefault(r["fdc_id"], {})[n] = float(r["amount"])
    serv = {}
    for r in rows(d, "food_portion.csv"):
        if r["fdc_id"] not in foods:
            continue
        try:
            g, amt = float(r["gram_weight"]), float(r["amount"] or 1)
        except ValueError:
            continue
        if g <= 0 or amt <= 0:
            continue
        if g > 5000:  # importer limit (ServingRow.grams)
            stats["servings_dropped_over_5000g"] += 1
            continue
        unit = units.get(r["measure_unit_id"], "")
        unit = "" if unit == "undetermined" else unit
        label = " ".join(x for x in (f"{amt:g}", unit, r["portion_description"].strip(), r["modifier"].strip()) if x)
        short = clip(label, 64)
        stats["serving_labels_shortened"] += short != label
        serv.setdefault(r["fdc_id"], {}).setdefault(short, round(g, 2))  # gram_weight covers the whole "amount unit" label
    out = []
    for fid, f in foods.items():
        v = nut.get(fid, {})
        kcal = next((v[k] for k in KCAL if k in v), None)
        row = {"kcal": kcal, **{NUT[k]: v[k] for k in NUT if k in v}}
        if any(row.get(k) is None for k in ("kcal", "protein_g", "fat_g", "carbs_g")):
            stats[f"skipped_missing_macro_{prefix}"] += 1
            continue
        if "fiber_g" not in row:  # no fibre nutrient row = not reported, NOT zero (IFCT blanks are 0)
            row["fiber_g"] = None
            stats[f"fiber_null_{prefix}"] += 1
        if any(x < 0 for x in row.values() if x is not None):
            stats[f"skipped_negative_{prefix}"] += 1
            continue
        if kcal is not None and 1008 not in v:
            stats[f"kcal_from_atwater_{prefix}"] += 1
        slug = f"usda-{prefix}-{fid}-{slugify(f['description'])}"[:128].rstrip("-")
        name = clip(f["description"], 128)  # slug above keeps the full description
        stats["names_truncated"] += name != f["description"]
        o = {"name": name, "slug": slug, **row, "verified": True,
             "sources": [SR_T if prefix == "sr" else FF_T],
             "servings": [{"label": k, "grams": g} for k, g in serv.get(fid, {}).items()]}
        if cats.get(f["food_category_id"]):
            o["category"] = cats[f["food_category_id"]]
        out.append(o)
    return out


if __name__ == "__main__":
    sr_dir, ff_dir, dest = sys.argv[1:4]
    stats = Counter()
    ff = load(ff_dir, "foundation_food", "ff", stats)
    sr = load(sr_dir, "sr_legacy_food", "sr", stats)
    have = {o["name"].casefold() for o in ff}
    dup = [o for o in sr if o["name"].casefold() in have]
    stats["sr_dropped_dup_of_foundation"] = len(dup)
    foods = ff + [o for o in sr if o["name"].casefold() not in have]
    srcs = [{"title": t, "url": "https://fdc.nal.usda.gov", "publisher": "USDA ARS", "evidence_level": NOTE}
            for t in (SR_T, FF_T)]
    with open(dest, "w", encoding="utf-8") as f:
        json.dump({"sources": srcs, "foods": foods}, f, ensure_ascii=False)
    stats["foods_written"] = len(foods)
    stats["foods_written_ff"] = len(ff)
    stats["with_servings"] = sum(bool(o["servings"]) for o in foods)
    print(dict(stats))
