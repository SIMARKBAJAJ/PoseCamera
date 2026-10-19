# Frontend data notes

## Files
- `data/common_foods.json`: 25 items `{label, slug, servingName}`. Every slug verified by SELECT on a copy of the DB (kcal/100 g checked, source IFCT or USDA). 14 IFCT, 11 USDA.
- `data/common_foods_notes.md`: dropped items (paneer, idli, dosa, upma, cooked poha) and caveats.
- `backend/plan_templates/`: `beginner-full-body-3x.json`, `push-pull-legs.json`, `upper-lower-4x.json`, `home-bodyweight-3x.json`. Plan slugs: `tpl-beginner-full-body-3x`, `tpl-push-pull-legs-6x`, `tpl-upper-lower-4x`, `tpl-home-bodyweight-3x`.

## Verification
- No dry-run exists, so plans were imported with `flask --app app import-plans <file>` into a third copy (`scratchpad/import_test.db`, via SAHIREP_DATABASE_URI plus a throwaway SAHIREP_JWT_SECRET). All 4 files: `errors: []`, 1 plan created each. Live DB untouched.
- SELECT on the copy (plan: days, slots): beginner 3d/15, home-bodyweight 3d/15, PPL 3d/16, upper-lower 4d/23.
- `plans.resolve_plan` on all 4 for gym, home, bodyweight: 0 unmet slots for gym/home. Bodyweight: home-bodyweight 0 unmet; the other three (gym plans) have 4-5 unmet bodyweight slots, as expected.

## Unresolved / notes
- Slots name movement patterns and muscles, not exercise slugs; the exercise is chosen when read (PLANS_SCHEMA.md). Fixed slugs are not part of the format.
- Plans are `verified: false` with no sources; set/rep/rest/RIR values are conventional, flagged in `evidence_notes`.
- PPL is a 3-day rotation with `days_per_week: 6` (same convention as the built-in generator: rotation run twice).
- IFCT has no serving rows and only raw foods; `servingName` is a UI suggestion, not a `food_servings` row. Cooked roti/paratha/rice/dal/egg come from USDA.
- Plans are not imported into the live DB; run `import-plans` per file when ready.
