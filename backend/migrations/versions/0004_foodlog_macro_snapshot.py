"""food_logs: per-100 g macro snapshot so re-importing a food cannot rewrite past days.

Backfills existing food entries from their current Food row (the best information available).
Quick-adds (food_id NULL) keep NULL snapshots and their own values.

Revision ID: 0004
Revises: 0003
"""
import sqlalchemy as sa
from alembic import op

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None

COLS = ["kcal_per100", "protein_g_per100", "carbs_g_per100", "fat_g_per100", "fiber_g_per100"]


def upgrade():
    with op.batch_alter_table("food_logs") as batch_op:
        for c in COLS:
            batch_op.add_column(sa.Column(c, sa.Float(), nullable=True))
    sets = ", ".join(
        f"{c} = (SELECT foods.{c.removesuffix('_per100')} FROM foods WHERE foods.id = food_logs.food_id)"
        for c in COLS
    )
    op.execute(f"UPDATE food_logs SET {sets} WHERE food_id IS NOT NULL")  # noqa: S608


def downgrade():
    with op.batch_alter_table("food_logs") as batch_op:
        for c in COLS:
            batch_op.drop_column(c)
