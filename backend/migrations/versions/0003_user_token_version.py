"""users.token_version: bumping it revokes every JWT issued to that user.

Revision ID: 0003
Revises: 0002
"""
import sqlalchemy as sa
from alembic import op

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade():
    with op.batch_alter_table("users") as batch_op:
        batch_op.add_column(
            sa.Column("token_version", sa.Integer(), nullable=False, server_default="0")
        )


def downgrade():
    # rebuilding users, which every user table references: FK off (see 0005/0006)
    sqlite = op.get_bind().dialect.name == "sqlite"
    if sqlite:
        with op.get_context().autocommit_block():
            op.execute("PRAGMA foreign_keys=OFF")
    with op.batch_alter_table("users") as batch_op:
        batch_op.drop_column("token_version")
    if sqlite:
        broken = op.get_bind().exec_driver_sql("PRAGMA foreign_key_check").fetchall()
        if broken:
            msg = f"foreign key violations after rebuilding users: {broken[:3]}"
            raise RuntimeError(msg)
        with op.get_context().autocommit_block():
            op.execute("PRAGMA foreign_keys=ON")
