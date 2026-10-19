# Catalog import schema

Run: `flask --app app import-catalog catalog.json` (or call `catalog.import_catalog(dict)`).
Upserts by key, so re-importing is idempotent. Bad rows are skipped and reported; the rest load.
Prints `{"created": {...}, "updated": {...}, "errors": [{"section", "index", "error"}]}`.

All sections are optional. Unknown fields are rejected. Import order: muscles, sources, machines, exercises.

```json
{
  "muscles": ["quadriceps"],
  "sources": [
    {"title": "...", "url": "...", "publisher": "...", "year": 2020, "evidence_level": "..."}
  ],
  "machines": [
    {"name": "...", "type": "machine|cable|free-weight|bodyweight",
     "description": "...", "verified": false, "sources": ["<source title>"]}
  ],
  "exercises": [
    {"name": "...", "slug": "lower-kebab-case", "primary_muscles": ["quadriceps"],
     "secondary_muscles": [], "machine": "<machine name>", "movement_pattern": "squat",
     (movement_pattern: free text or null; vocabulary in PLANS_SCHEMA.md, defined by RULES in data/convert/exercises.py)
     "difficulty": "beginner|intermediate|advanced", "instructions": "...",
     "met": 5.0, "has_pose_tracking": false, "verified": false, "sources": ["<source title>"]}
  ]
}
```

Keys: muscle `name`, source `title`, machine `name`, exercise `slug`.
Muscles named by an exercise are created automatically. `machine` and `sources` must already exist
(earlier in the file or in the DB) or the row errors. Only `title`/`name`/`type`/`slug`/
`primary_muscles`/`difficulty` are required.
`verified: true` is stored only if the row cites at least one source; unsourced rows stay `false`.
