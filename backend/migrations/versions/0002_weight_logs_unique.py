"""Reconcile drifted live DBs: weight_logs UNIQUE(user_id, date).

No-op on DBs created from 0001 (constraint already present).

Revision ID: 0002
Revises: 0001
"""
import sqlalchemy as sa
from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None

COLS = ["user_id", "date"]


def upgrade():
    insp = sa.inspect(op.get_bind())
    has = any(
        c["column_names"] == COLS for c in insp.get_unique_constraints("weight_logs")
    ) or any(i["unique"] and i["column_names"] == COLS for i in insp.get_indexes("weight_logs"))
    if has:
        return
    # keep the latest (highest id) row per (user_id, date)
    op.execute(
        "DELETE FROM weight_logs WHERE id NOT IN "
        "(SELECT MAX(id) FROM weight_logs GROUP BY user_id, date)"
    )
    with op.batch_alter_table("weight_logs") as batch_op:
        batch_op.create_unique_constraint("uq_weight_logs_user_id_date", COLS)


def downgrade():
    pass  # reconciliation only; dropped rows cannot be restored
