"""foods.fiber_g becomes nullable: null = not reported (USDA), 0 = measured/below detection.

SQLite cannot drop NOT NULL in place, so the table is rebuilt (batch mode). The rebuild DROPs
`foods`, which food_logs/food_servings/foods_sources reference; with foreign_keys=ON (the app
sets it) that DROP fails. PRAGMA foreign_keys is a no-op inside a transaction, hence the
autocommit blocks, and foreign_key_check afterwards proves no reference was broken.

Revision ID: 0005
Revises: 0004
"""
import sqlalchemy as sa
from alembic import op

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def _rebuild(nullable):
    sqlite = op.get_bind().dialect.name == "sqlite"
    if sqlite:
        with op.get_context().autocommit_block():
            op.execute("PRAGMA foreign_keys=OFF")
        op.execute("DROP TABLE IF EXISTS _alembic_tmp_foods")  # leftover of a failed rebuild
    with op.batch_alter_table("foods") as batch_op:
        batch_op.alter_column("fiber_g", existing_type=sa.Float(), nullable=nullable)
    if sqlite:
        broken = op.get_bind().exec_driver_sql("PRAGMA foreign_key_check").fetchall()
        if broken:
            msg = f"foreign key violations after rebuilding foods: {broken[:3]}"
            raise RuntimeError(msg)
        with op.get_context().autocommit_block():
            op.execute("PRAGMA foreign_keys=ON")


def upgrade():
    _rebuild(nullable=True)


def downgrade():
    op.execute("UPDATE foods SET fiber_g = 0 WHERE fiber_g IS NULL")  # lossy: null -> 0
    _rebuild(nullable=False)
