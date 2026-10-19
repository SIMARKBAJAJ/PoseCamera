"""workout_sets.clean_count / attempted_count / formscore become nullable.

Non-pose-tracked ("other") exercises have no form data; null = not measured. squat/push-up
sets still always carry all three (enforced by the API, not the table).

SQLite cannot drop NOT NULL in place, so workout_sets is rebuilt (batch mode). Nothing
references workout_sets, so the DROP is safe with foreign_keys=ON (see 0006); the named
CHECKs and fk_workout_sets_exercise_slug are reflected and carried over, and
foreign_key_check afterwards proves no reference was broken.

Revision ID: 0007
Revises: 0006
"""
import sqlalchemy as sa
from alembic import op

revision = "0007"
down_revision = "0006"
branch_labels = None
depends_on = None

COLUMNS = (("clean_count", sa.Integer()), ("attempted_count", sa.Integer()), ("formscore", sa.Float()))


def _rebuild(nullable):
    op.execute("DROP TABLE IF EXISTS _alembic_tmp_workout_sets")  # leftover of a failed rebuild
    with op.batch_alter_table("workout_sets") as batch_op:
        for name, type_ in COLUMNS:
            batch_op.alter_column(name, existing_type=type_, nullable=nullable)
    if op.get_bind().dialect.name == "sqlite":
        broken = op.get_bind().exec_driver_sql("PRAGMA foreign_key_check").fetchall()
        if broken:
            msg = f"foreign key violations after rebuilding workout_sets: {broken[:3]}"
            raise RuntimeError(msg)


def upgrade():
    _rebuild(nullable=True)


def downgrade():
    # lossy: unmeasured form data becomes "all reps clean, score 0" (keeps clean + attempted = reps)
    op.execute("UPDATE workout_sets SET clean_count = rep_count WHERE clean_count IS NULL")
    op.execute("UPDATE workout_sets SET attempted_count = 0 WHERE attempted_count IS NULL")
    op.execute("UPDATE workout_sets SET formscore = 0 WHERE formscore IS NULL")
    _rebuild(nullable=False)
