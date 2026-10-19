# Muscle recovery: what the sources say (and what is only a project default)

Status: research notes for the Recovery feature. Not medical advice. Written 2026-10-19.
Only sources that were actually opened while writing this are listed. Claims without such a source were dropped.

## Bottom line
- There is **no single universal recovery number**. The sources below do not give one number of hours per muscle.
- The user's remembered "24 + 22 hours" could **not** be confirmed in any source opened. It is not used anywhere in the app.
- Everything the app shows as "hours left" is a **project default**, derived from ranges that the sources support only loosely. The app must say so.

## Sources opened

### S1. ACSM Position Stand, 2026
Phillips SM, et al. *American College of Sports Medicine Position Stand. Resistance Training Prescription for Muscle Function, Hypertrophy, and Physical Performance in Healthy Adults: An Overview of Reviews.* Published by Wolters Kluwer Health on behalf of the American College of Sports Medicine; issue date April 2026 (accepted Sept 2025). The page text I read does not name the journal title; its supplementary link is hosted under the MSSE site, so it appears to be Medicine & Science in Sports & Exercise (not confirmed from the text). https://pmc.ncbi.nlm.nih.gov/articles/PMC12965823

What the source says (overview of reviews, so it summarises other reviews):
- Hypertrophy was enhanced by higher volume (**>=10 sets per muscle group per week**) and by eccentric contractions.
- Resistance training frequency made **no difference to hypertrophy when total volume was equated** (comparison reported: 1 day/week vs >5 days/week).
- Strength was enhanced by training with **>=2 sessions/week**; the authors note the upper limit cannot be determined from their analysis.
- Training to muscular failure did not enhance gains in strength, hypertrophy or power. The authors propose sufficient effort can be reached with "near-failure", a target of **2-3 repetitions in reserve (RIR)**, and say failure may be inadvisable for some groups (e.g. older people).
- It recaps the earlier (2009) guidance of 2-3 sessions per week for healthy adults and 2-3 min rest between sets.
- It does **not** (in the passages checked) give a number of hours of rest needed before training the same muscle again.
- Limitation stated by the authors: overviews of reviews cannot compare different prescriptions against each other.

### S2. Damas et al., 2016
Damas F, Phillips SM, Ugrinowitsch C, and co-authors (only these three names were confirmed on the page; the full author list was not checked). *Resistance training-induced changes in integrated myofibrillar protein synthesis are related to hypertrophy only after attenuation of muscle damage.* The Journal of Physiology, 2016 (issue date 15 Sept 2016; volume and page numbers not confirmed from the text). https://pmc.ncbi.nlm.nih.gov/articles/PMC5023708/

What the source says (10 young men, vastus lateralis only, measured at rest, 24 h and 48 h after one bout, in training week 1, week 3 and week 10):
- Maximal voluntary contraction (MVC) torque after the bout, as % of baseline: **week 1: about 78% at 24 h and about 78% at 48 h**; week 3: about 97% (24 h), 98% (48 h); week 10: about 94% (24 h), 98% (48 h).
- Muscle soreness (mm, higher = sorer): week 1: 40 at 24 h and 61 at 48 h; weeks 3 and 10: about 8-10.
- Creatine kinase at 48 h: week 1: about 416 U/l; week 3: about 226; week 10: about 110 (baseline about 107-132).
- Myofibrillar protein synthesis was measured over the first 48 h after a bout and was **higher after the first bout (untrained state) than after later bouts**; the authors say the response is attenuated in the trained state.
- The authors speculate the early protein-synthesis rise is more about repairing damage from an unaccustomed bout than about growth.

Caveats: one muscle (quadriceps), ten young men, one exercise protocol. It shows that the same bout costs far less strength and soreness once you are accustomed to it. It does not say how many hours any muscle needs.

## Sources searched but NOT opened (not used)
- Schoenfeld, Ogborn, Krieger 2016 (frequency meta-analysis): PubMed page could not be opened (cookie wall). Not cited. S1 covers the frequency question more recently.
- Search snippets about DOMS peaking about 24-72 h and strength deficits lasting 48-96 h after eccentric exercise were seen only as search-result summaries. Not cited.
- A 2021 Frontiers in Physiology PDF could not be read (binary text). Not cited.

## What the app does (project defaults, NOT from a source)
These are design choices informed by S1/S2 and are labelled "unverified project defaults" in the app:
1. Recovery state is estimated from logged sets: when a muscle was last worked, how many hard sets, how hard.
2. Larger muscles get a longer default window than smaller ones; secondary muscles count about half. The specific hours are our defaults, not source values.
3. More hard sets and near-failure work lengthen the window; light work shortens it. (S1 supports volume mattering and that failure is not required; it gives no hour multipliers.)
4. A "recovery speed" setting (slower / normal / faster) lets the user adjust, because S2 shows recovery varies a lot with how accustomed you are.
5. The app only warns; it never blocks a workout. Anyone with pain, injury, or illness should follow professional advice, not this timer.
