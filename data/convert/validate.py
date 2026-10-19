"""Import both outputs into a TEMP sqlite db and print counts.

Usage: python -I validate.py <backend_dir> <foods.json> <exercises.json>
"""
import json
import os
import sys
import tempfile
from collections import Counter

backend, foods_p, ex_p = sys.argv[1:4]
sys.path.insert(0, backend)
os.environ.setdefault("SAHIREP_JWT_SECRET", "x" * 40)
from app import create_app  # noqa: E402
from catalog import Exercise, Machine, import_catalog  # noqa: E402
from nutrition import Food, import_foods  # noqa: E402

with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as td:
    app = create_app(database_uri=f"sqlite:///{td}/t.db".replace("\\", "/"))
    with app.app_context():
        for fn, p in ((import_foods, foods_p), (import_catalog, ex_p)):
            r = fn(json.load(open(p, encoding="utf-8")))
            print(fn.__name__, {k: (v if k != "errors" else len(v)) for k, v in r.items() if k != "warnings"},
                  "warnings", len(r.get("warnings", [])), r["errors"][:3])
        foods = Food.query.all()
        print("foods", len(foods), "with servings %.1f%%" % (100 * sum(bool(f.servings) for f in foods) / len(foods)))
        ex = Exercise.query.all()
        print("exercises", len(ex), "verified", sum(e.verified for e in ex))
        print("patterns", dict(Counter(e.movement_pattern for e in ex)))
        print("muscles", dict(Counter(m.name for e in ex for m in e.primary_muscles).most_common()))
        print("machines", [(m.name, m.type) for m in Machine.query.all()])
