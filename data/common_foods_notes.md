# Common foods: dropped items and caveats

No catalog match (searched name LIKE on copy of the DB), dropped:
- Paneer: only USDA cottage cheese (`usda-sr-172179-cheese-cottage-creamed-large-or-small-curd`, 98 kcal) and tofu (`usda-sr-172475-tofu-raw-firm-prepared-with-calcium-sulfate`). Different foods (paneer ~ 265 kcal/100 g), not substituted.
- Idli, dosa, upma: no rows. Nearest raw ingredients: `ifct-a022-wheat-semolina`, `ifct-a015-rice-raw-milled`, `ifct-b003-black-gram-dal`. Users should quick-add or log ingredients.
- Poha (cooked): none. The list uses raw flattened rice (`ifct-a011-rice-flakes`); the only name hit for "poha" is a fruit (cape gooseberry).

Caveats:
- IFCT 2017 (121 rows) is raw ingredients only, with no serving rows; `servingName` values are suggested UI labels, not DB servings. Cooked items come from USDA.
- Roti, paratha are USDA commercial/frozen products (no IFCT cooked roti). Raw `ifct-a019-wheat-flour-atta` is also listed.
- Curd uses USDA plain whole-milk yogurt (closest to dahi); chaas uses USDA cultured lowfat buttermilk.
- Carbs are not comparable across IFCT (available carbs) and USDA (by difference); see NUTRITION_SCHEMA.md.
- Ghee/Oats/Egg/Rice (cooked) are USDA because IFCT has no row (egg: IFCT has only raw).
- Other candidates seen: `ifct-b013-lentil-dal` (masoor, raw), `ifct-b004-black-gram-whole`, `ifct-m001-egg-poultry-whole-raw`, `ifct-e009-banana-ripe-montham`, `ifct-h005-cashew-nut`, `ifct-h021-walnut`.
