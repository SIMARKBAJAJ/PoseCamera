"""Sets for any exercise (slug, load, rpe, set_type), users.consent_at, pose-tracked flags.

workout_sets is rebuilt in batch mode (new FK/CHECKs). Nothing references workout_sets, so the
DROP is safe with foreign_keys=ON; foreign_key_check afterwards proves it. users only gains a
nullable column (plain ALTER, no rebuild, so its many referrers are untouched).

Data step: the canonical pose-tracked exercises are `bodyweight-squat` and `pushups` (the plain
"Pushups" row); the Android client's squat / push-up sets map to them.

Revision ID: 0006
Revises: 0005
"""
import sqlalchemy as sa
from alembic import op

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None

POSE_SLUGS = ("bodyweight-squat", "pushups")


def upgrade():
    with op.batch_alter_table("workout_sets") as batch_op:
        batch_op.add_column(sa.Column("exercise_slug", sa.String(128), nullable=True))
        batch_op.add_column(sa.Column("load_kg", sa.Float(), nullable=True))
        batch_op.add_column(sa.Column("rpe", sa.Float(), nullable=True))
        batch_op.add_column(
            sa.Column("set_type", sa.String(7), nullable=False, server_default="working")
        )
        batch_op.create_foreign_key(
            "fk_workout_sets_exercise_slug", "exercises", ["exercise_slug"], ["slug"]
        )
        batch_op.create_check_constraint("ck_workout_sets_load_kg", "load_kg BETWEEN 0 AND 1000")
        batch_op.create_check_constraint("ck_workout_sets_rpe", "rpe BETWEEN 1 AND 10")
        batch_op.create_check_constraint(
            "ck_workout_sets_set_type", "set_type IN ('warmup','working','drop','failure')"
        )
    with op.batch_alter_table("users") as batch_op:
        batch_op.add_column(sa.Column("consent_at", sa.DateTime(timezone=True), nullable=True))
    op.execute(
        sa.text("UPDATE exercises SET has_pose_tracking = :flag WHERE slug IN :slugs").bindparams(
            sa.bindparam("slugs", POSE_SLUGS, expanding=True),
            sa.bindparam("flag", True, type_=sa.Boolean()),  # Postgres booleans reject = 1
        )
    )
    if op.get_bind().dialect.name == "sqlite":
        broken = op.get_bind().exec_driver_sql("PRAGMA foreign_key_check").fetchall()
        if broken:
            msg = f"foreign key violations after rebuilding workout_sets: {broken[:3]}"
            raise RuntimeError(msg)


def downgrade():
    # dropping users.consent_at rebuilds users, which every user table references (see 0005)
    sqlite = op.get_bind().dialect.name == "sqlite"
    if sqlite:
        with op.get_context().autocommit_block():
            op.execute("PRAGMA foreign_keys=OFF")
    op.execute(
        sa.text("UPDATE exercises SET has_pose_tracking = :flag WHERE slug IN :slugs").bindparams(
            sa.bindparam("slugs", POSE_SLUGS, expanding=True),
            sa.bindparam("flag", False, type_=sa.Boolean()),  # Postgres booleans reject = 1
        )
    )
    # lossy: sets of other exercises have no representable type in the old schema
    op.execute("DELETE FROM workout_sets WHERE exercise_type NOT IN ('squat','push-up')")
    with op.batch_alter_table("users") as batch_op:
        batch_op.drop_column("consent_at")
    with op.batch_alter_table("workout_sets") as batch_op:
        batch_op.drop_constraint("ck_workout_sets_set_type", type_="check")
        batch_op.drop_constraint("ck_workout_sets_rpe", type_="check")
        batch_op.drop_constraint("ck_workout_sets_load_kg", type_="check")
        batch_op.drop_constraint("fk_workout_sets_exercise_slug", type_="foreignkey")
        for column in ("set_type", "rpe", "load_kg", "exercise_slug"):
            batch_op.drop_column(column)
    if sqlite:
        with op.get_context().autocommit_block():
            op.execute("PRAGMA foreign_keys=ON")
