"""free-exercise-db exercises.json -> catalog importer JSON. Stdlib only.

Usage: python -I exercises.py <exercises.json> <out.json>
"""
import json
import re
import sys
from collections import Counter

SRC = "free-exercise-db (yuhonas)"
# The pose-tracked squat/push-up pair; must match POSE_SLUG in backend/app.py.
POSE_SLUGS = {"bodyweight-squat", "pushups"}
LEVEL = {"beginner": "beginner", "intermediate": "intermediate", "expert": "advanced"}

# source muscle -> plan muscle name. Anything not listed keeps its original name.
MUSCLE = {"lats": "back", "middle back": "back", "lower back": "back", "traps": "back"}

# equipment -> Machine type. 'other' and null equipment get NO machine (unknown, not guessed).
# ponytail: bands/balls/foam roll have no exact type; free-weight is the nearest non-machine, non-bodyweight bucket.
EQUIP = {"barbell": "free-weight", "dumbbell": "free-weight", "kettlebells": "free-weight",
         "e-z curl bar": "free-weight", "bands": "free-weight", "medicine ball": "free-weight",
         "exercise ball": "free-weight", "foam roll": "free-weight",
         "cable": "cable", "machine": "machine", "body only": "bodyweight"}
MACHINE_NAME = {"e-z curl bar": "E-Z Curl Bar"}

# Movement pattern rules: first match wins; otherwise null. Each rule:
# (pattern, name regex, required mapped primary muscle (any of), excluded name regex or None)
# Exercises in categories stretching/plyometrics/olympic weightlifting/cardio/strongman are never assigned.
SKIP_CATEGORY = {"stretching", "plyometrics", "olympic weightlifting", "cardio", "strongman"}
RULES = [
    ("lunge", r"lunge|split squat|step-?up", {"quadriceps", "glutes", "hamstrings"}, None),
    ("squat", r"squat|leg press", {"quadriceps", "glutes"}, None),
    ("hinge", r"deadlift|good morning|hip thrust|romanian|glute bridge|pull.?through",
     {"glutes", "hamstrings", "back"}, None),
    ("knee_flexion", r"leg curl|hamstring curl|nordic|glute.?ham raise|slider.*curl", {"hamstrings"}, None),
    ("elbow_flexion", r"curl", {"biceps"}, r"leg curl|hamstring"),
    ("elbow_extension", r"extension|pushdown|push-down|kickback|skull|\bdips?\b", {"triceps"}, None),  # chest dips stay null: unsure
    ("vertical_push", r"shoulder press|overhead press|military press|arnold|push press|handstand push|pike push",
     {"shoulders"}, None),
    ("horizontal_push", r"bench press|push-?up|chest press|floor press", {"chest", "triceps", "shoulders"},
     r"incline|handstand|pike|pull"),
    ("vertical_pull", r"pull-?up|chin-?up|\bchins?\b|pulldown|pull-down", {"back"}, r"push"),
    ("horizontal_pull", r"\brows?\b", {"back"}, r"upright"),
    # Isolation / core / accessory patterns. Appended AFTER the rules above so exercises they already
    # matched keep their pattern; these only claim names the rules above left null.
    ("lateral_raise", r"lateral raise|side laterals?", {"shoulders"}, r"rear|bent|front|reverse"),
    ("front_raise", r"front.*raise", {"shoulders"}, r"lateral"),
    ("rear_delt", r"rear|reverse fl|back fl|face pull|pull apart|bent over.*lateral", {"shoulders"}, r"upright"),
    ("upright_row", r"upright", {"back", "shoulders"}, None),
    ("shrug", r"shrug", {"back"}, None),
    ("chest_fly", r"\bfl(?:y|ye)s?\b|crossover|cross over|butterfly", {"chest"}, r"reverse|rear|push up"),
    ("calf_raise", r"calf raise|calf press", {"calves"}, r"reverse"),
    ("wrist_curl", r"wrist curl|finger curl", {"forearms"}, None),
    ("core_stability", r"plank|side bridge|rollout|ab roller|dead bug|pallof|fallout", {"abdominals"}, None),
    ("core_rotation", r"twist|wood chop|oblique", {"abdominals"}, None),
    ("core_flexion", r"crunch|sit-?up|leg raise|knee.?raise|hip raise|pull-in|jackknife|leg tuck|hanging pike",
     {"abdominals"}, r"side|cross-body|slam|windmill"),
    ("hip_extension", r"hyperextension|kickback|hip extension|hip bridge|butt lift|hip lift",
     {"glutes", "hamstrings", "back"}, None),
    ("neck", r"neck", {"neck"}, r"press|behind|to neck"),
]


# MET (Compendium of Physical Activities 2024, values unchanged, see data/out/met_reference.json):
# Barbell AND pattern in (squat, hinge) -> 6.0 (code 02050 vigorous resistance training);
# any other non-null movement_pattern -> 3.5 (code 02054 resistance training, multiple exercises);
# null movement_pattern -> no MET. Category default, not per-exercise measured.
MET_SRC = "Compendium of Physical Activities 2024 (Herrmann et al.)"


def met(machine, mp):
    if mp is None:
        return None
    return 6.0 if machine == "Barbell" and mp in ("squat", "hinge") else 3.5


def slugify(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def pattern(e, primary):
    if e["category"] in SKIP_CATEGORY:
        return None
    name = e["name"].lower()
    for pat, rx, muscles, excl in RULES:
        if re.search(rx, name) and not (excl and re.search(excl, name)) and muscles & set(primary):
            return pat
    return None


def main(src, dest):
    data = json.load(open(src, encoding="utf-8"))
    machines, exercises, stats, seen = {}, [], Counter(), set()
    for e in data:
        mp = list(dict.fromkeys(MUSCLE.get(m, m) for m in e["primaryMuscles"]))
        ms = [m for m in dict.fromkeys(MUSCLE.get(m, m) for m in e["secondaryMuscles"]) if m not in mp]
        eq = e["equipment"]
        mname = None
        if eq in EQUIP:
            mname = MACHINE_NAME.get(eq, eq.title())
            machines[mname] = EQUIP[eq]
        slug = slugify(e["id"]) or slugify(e["name"])
        if slug in seen or not mp:
            stats["skipped_" + ("dup_slug" if slug in seen else "no_primary_muscle")] += 1
            continue
        seen.add(slug)
        pat = pattern(e, mp)
        m = met(mname, pat)
        exercises.append({
            "name": e["name"], "slug": slug, "primary_muscles": mp, "secondary_muscles": ms,
            "machine": mname, "movement_pattern": pat, "difficulty": LEVEL[e["level"]],
            "met": m, "instructions": "\n".join(e["instructions"]) or None, "verified": False,
            "has_pose_tracking": slug in POSE_SLUGS,
            "sources": [SRC] + ([MET_SRC] if m is not None else [])})
    muscles = sorted({m for x in exercises for m in x["primary_muscles"] + x["secondary_muscles"]})
    out = {"muscles": muscles,
           "sources": [{"title": SRC, "url": "https://github.com/yuhonas/free-exercise-db",
                        "evidence_level": "Unlicense; no research citation"},
                       {"title": MET_SRC, "publisher": "Arizona State University / Compendium of Physical Activities",
                        "url": "https://pacompendium.com", "year": 2024,
                        "evidence_level": "MET category default, not per-exercise measured"}],
           "machines": [{"name": n, "type": t, "verified": False} for n, t in sorted(machines.items())],
           "exercises": exercises}
    with open(dest, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False)
    print(dict(stats), len(exercises), Counter(x["movement_pattern"] for x in exercises), len(machines))


if __name__ == "__main__":
    main(*sys.argv[1:3])
