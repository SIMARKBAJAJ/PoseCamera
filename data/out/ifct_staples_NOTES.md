# ifct_staples.json notes

Source: Indian Food Composition Tables 2017 (IFCT 2017), ICMR-National Institute of Nutrition, https://nin.res.in/ebooks/IFCT2017.pdf (Table 1 proximates/fibre, Table 5 minerals, regional-names appendix for Hindi names).

## Access
PDF (~21 MB) was streamed into memory with Python (urllib + pypdf, empty-password decrypt) and never written to disk. Only extracted text/fragments went to a temp scratch dir (deleted afterwards). WebFetch refused it (>10 MB).

## Counts
- 121 foods, 1 source row. Import test into a temp sqlite DB: created 121 foods + 1 source, 0 errors; 103 stored verified=true.
- verified=true (103): the Table 1 row had all 9 value columns, so every number is read directly.
- verified=false (18): the table's blank cells (below detection limit) collapse in the PDF text, so some columns had to be placed by checking that water+protein+ash+fat+fibre+carbohydrate ~ 100 and that energy ~ 4P+4C+9F. Values are still from the table, but column placement is inferred. Rows: jaggery, buffalo/cow milk, 2 eggs, chicken breast/liver, goat shoulder/chops/liver, Bombay duck, hilsa, mackerel, white pomfret, sardine, tuna, catla, rohu.
- Blank table cells (fat, fibre, carbohydrate) are stored as 0 (table says blank = below detectable limit).
- kcal = IFCT kJ / 4.184, rounded to 0.1 (IFCT gives kJ only). Fibre = total dietary fibre (FIBTG); carbs = available carbohydrate (CHOAVLDF).
- iron_mg 6, calcium_mg 6, sodium_mg 29, name_local (Hindi, H. entries from the names appendix) 100 foods. Minerals are filled only when the Table 5 row had all 10 columns (otherwise column position is ambiguous, left null). Vitamin B12 not tabulated in IFCT, so omitted. No servings.

## Skipped (not written)
- G010 curry leaves: energy vs macro sanity check failed (cannot trust column placement).
- L003 paneer, L004 khoa, M004 boiled poultry egg, N001 chicken leg skinless, Q008 tiger prawns orange: row truncated/incomplete in extracted text, no unique column mapping.
- Not in IFCT or no usable row: oats, white refined sugar, edible oils/ghee/butter (Table 12 only has fatty-acid profiles, no proximate/energy row), curd, beef/pork not requested.
- Many other IFCT foods exist (about 528 rows) but were not selected.

## License caveat
IFCT 2017 is (c) ICMR-National Institute of Nutrition, no open license. User explicitly accepted the copyright risk for hand-entered values; every row cites the source. Ask NIN for permission before any commercial redistribution: ifct2017@gmail.com.
