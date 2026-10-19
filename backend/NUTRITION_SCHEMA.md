# Food import schema

Run: `flask --app app import-foods foods.json` (or call `nutrition.import_foods(dict)`).
Upserts by key, so re-importing is idempotent. Bad rows are skipped and reported; the rest load.
Prints `{"created": {...}, "updated": {...}, "errors": [{"section", "index", "error"}]}`.

Sections are optional, unknown fields are rejected. Import order: sources, foods.
Sources are the same `sources` table as the exercise catalog (see CATALOG_SCHEMA.md).

```json
{
  "sources": [
    {"title": "IFCT 2017", "url": "...", "publisher": "ICMR-NIN", "year": 2017, "evidence_level": "..."}
  ],
  "foods": [
    {"name": "...", "name_local": "...", "slug": "lower-kebab-case",
     "category": "cereal", "region": "north-indian",
     "kcal": 0, "protein_g": 0, "carbs_g": 0, "fat_g": 0, "fiber_g": 0,
     "sugar_g": null, "sodium_mg": null, "iron_mg": null, "calcium_mg": null, "vitamin_b12_ug": null,
     "verified": false, "sources": ["<source title>"],
     "servings": [{"label": "1 roti", "grams": 40}]}
  ]
}
```

All nutrient values are per 100 g and must be >= 0. Key: food `slug`; source `title`.
Required: `name`, `slug`, `kcal`, `protein_g`, `carbs_g`, `fat_g`. Others optional.
`fiber_g: null` (or omitted) means "not reported" and is stored and returned as `null`; `0` means
measured zero. USDA foods without a fibre nutrient row are `null`; IFCT blanks are `0` (IFCT defines
blank as below detection). Day fibre totals count `null` as 0 without inventing a value.

## Data semantics (values are stored exactly as published; none were altered)

- **Carbohydrate differs by source.** IFCT: *available* carbohydrate, fibre excluded
  (`CHOAVLDF`). USDA: carbohydrate *by difference*, fibre included. Do not compare `carbs_g`
  across the two sources, or compute net carbs, without accounting for this.
- **kcal need not equal 4P + 4C + 9F.** Alcohol (7 kcal/g, not in the macros) and leafy greens and
  other foods with specific Atwater factors or rounding make the energy figure differ from the
  4/4/9 sum. `kcal` is the published energy value; never "fix" it from the macros.
- **Names are not unique; `slug` is.** 47 food names occur more than once (96 rows). Look foods up by `slug`.
- **Log snapshots.** A food log copies the food's per-100 g kcal/protein/carbs/fat/fibre when it
  is created (`FoodLog.*_per100`) and computes everything from that copy, so re-importing a changed
  food never rewrites past days. Quick adds keep their own values.
`sources` must already exist (earlier in the file or in the DB) or the row errors.
`verified: true` is stored only if the row cites at least one source; unsourced rows stay `false`.
`servings` replace the food's serving list (matched by `label`, so existing serving ids survive).

# API (JWT = `Authorization: Bearer <token>`)

Public: `GET /api/foods?q=&category=&region=` (q matches name or name_local), `GET /api/foods/<slug>`.

JWT, own data only (other users' rows are 404):

- `PUT|GET /api/profile`: `sex male|female`, `birth_year` (1900..current year), `height_cm`, `weight_kg`,
  `activity_level sedentary|light|moderate|active|very_active`, `goal lose|maintain|gain`,
  optional `diet_pref veg|egg|nonveg|vegan`.
- `GET /api/nutrition/targets`: kcal, protein_g, carbs_g, fat_g, bmr (estimates; 404 without profile).
- `POST /api/nutrition/log`: `meal breakfast|lunch|snack|dinner`, optional `date` (default today in SAHIREP_TZ, default Asia/Kolkata), then
  either `food_slug` + (`quantity_g` or `serving_id`), or quick add `name` + `kcal` (+ `protein_g`, `carbs_g`, `fat_g`).
- `GET /api/nutrition/log?date=`, `PATCH|DELETE /api/nutrition/log/<id>`. PATCH: `meal`, `date`, plus
  `quantity_g` (food entries) or `kcal/protein_g/carbs_g/fat_g` (quick adds). Food macros are
  computed at read time from the entry's own per-100 g snapshot (entries also carry `fiber_g`, null if unknown). Editing `quantity_g` clears the entry's serving. Quick adds reject `quantity_g`/`serving_id`. The `lose` kcal target is floored at ~1200 (female) / ~1500 (male) kcal, an approximate conservative rule of thumb.
- `GET /api/nutrition/day?date=`: `totals`, `fiber_g` (day total, unknown counts as 0), per-`meals` entries and totals, `targets`, `remaining`
  (kcal, protein_g). `targets`/`remaining` are null without a profile.
- `POST /api/weight {kg, date?}` (one per day, same date replaces), `GET /api/weight`, `DELETE /api/weight/<id>`.

Formulas and constants (all estimates) live in `nutrition.py` with citations: Mifflin-St Jeor BMR,
activity factors 1.2-1.9, goal adjustment lose -15% / gain +7.5%, protein 1.6-2.0 g/kg (ISSN),
fat floor max(0.8 g/kg, 20% energy), carbs = remaining energy, exercise kcal = MET x kg x hours.

# Sets, analytics and data rights (JWT, own data only)

- `POST /api/sets` (unchanged for the Android client) also accepts optional `exercise_slug` (must exist, else 400),
  `load_kg` (0..1000), `rpe` (1..10), `set_type warmup|working|drop|failure` (default `working`).
  `exercise_type` is `squat`|`push-up` (pose-tracked; a given slug must be `bodyweight-squat` / `pushups`) or `other`
  (slug required, exercise must not be pose-tracked). `clean_count`/`attempted_count`/`formscore` stay required.
  `/api/sets` and `/api/history` return the new keys (`null` for old rows; `set_type` is `working`).
- `GET /api/me/prs?limit=&offset=`: per `exercise_slug`, `heaviest_kg` and `best_e1rm` (best set by Epley
  1RM = load * (1 + reps/30), Epley 1985, an estimate; only reps <= 12 and load > 0, else null).
- `GET /api/me/volume?weeks=1..26` (default 4): `items` of `{week (ISO), week_start, muscle, hard_sets}`; hard sets =
  non-warmup sets, counted once per primary muscle, weeks in SAHIREP_TZ.
- `GET /api/me/export`: all the user's data as a JSON attachment (no password hash).
- `DELETE /api/me {password}`: erases the user and all their rows (204); wrong password 401; rate-limited like login.
- `POST /api/auth/register` accepts optional `consent: true`, stored as `users.consent_at`.
