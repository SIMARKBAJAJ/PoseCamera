"""Sync model: client_id + updated_at on user-owned tables, sync_changes, sync_ops, catalog_meta.

client_id (UUID, unique per user; fixed 'profile' / 'plan' for the two singleton rows) and
updated_at (last-writer-wins clock) are added NULLABLE and backfilled. Nullable on purpose: the
ORM always fills them, but raw-SQL writers of old (and the existing migration tests) insert rows
without them; a NOT NULL would also force a table rebuild. So upgrade only ALTERs ADD COLUMN and
CREATE UNIQUE INDEX: no rebuild, no foreign_keys=OFF. Downgrade drops columns, which rebuilds the
tables on SQLite (user_plans is referenced by plan_completions), so it uses the 0005 FK-off
pattern; on Postgres they are plain ALTERs.

Existing rows get one 'upsert' sync_changes row each, so a fresh device pulling from cursor 0
receives everything. Tombstones are op='delete' rows in sync_changes (no deleted_at column).
catalog_meta is seeded with version 1 per entity: the catalog already holds data.
Downgrade drops all of it (sync state is lossy by nature; the data rows are kept).

Revision ID: 0008
Revises: 0007
"""
from datetime import UTC, datetime
from uuid import uuid4

import sqlalchemy as sa
from alembic import op

revision = "0008"
down_revision = "0007"
branch_labels = None
depends_on = None

# table -> (sync entity, fixed client_id or None, unique index (name, columns) or None)
TABLES = {
    "user_profiles": ("profile", "profile", None),
    "user_plans": ("user_plans", "plan", None),
    "plan_completions": (
        "plan_completions", None, ("uq_plan_completions_plan_client", ["user_plan_id", "client_id"])
    ),
    "weight_logs": (
        "weight_logs", None, ("uq_weight_logs_user_id_client_id", ["user_id", "client_id"])
    ),
    "food_logs": ("food_logs", None, ("uq_food_logs_user_id_client_id", ["user_id", "client_id"])),
    "workout_sets": (
        "workout_sets", None, ("uq_workout_sets_user_id_client_id", ["user_id", "client_id"])
    ),
}
CATALOG = ("foods", "exercises", "machines", "muscles", "plans")
STAMP = sa.DateTime(timezone=True)


def _fk_off(*, off):  # downgrade only: SQLite rebuilds referenced tables (see 0005)
    if op.get_bind().dialect.name != "sqlite":
        return
    if not off:
        broken = op.get_bind().exec_driver_sql("PRAGMA foreign_key_check").fetchall()
        if broken:
            msg = f"foreign key violations after rebuilding sync tables: {broken[:3]}"
            raise RuntimeError(msg)
    with op.get_context().autocommit_block():
        op.execute(f"PRAGMA foreign_keys={'OFF' if off else 'ON'}")


def _drop_leftover(table):  # leftover of a failed SQLite rebuild
    if op.get_bind().dialect.name == "sqlite":
        op.execute(f"DROP TABLE IF EXISTS _alembic_tmp_{table}")


def upgrade():
    bind = op.get_bind()
    now = datetime.now(UTC)
    for table, (_entity, fixed, unique) in TABLES.items():
        op.add_column(table, sa.Column("client_id", sa.String(64), nullable=True))
        op.add_column(table, sa.Column("updated_at", STAMP, nullable=True))
        ids = bind.execute(sa.text(f"SELECT id FROM {table}")).scalars().all()  # noqa: S608
        if ids:
            bind.execute(
                sa.text(f"UPDATE {table} SET client_id = :c, updated_at = :t WHERE id = :i")  # noqa: S608
                .bindparams(sa.bindparam("t", type_=STAMP)),
                [{"c": fixed or str(uuid4()), "t": now, "i": i} for i in ids],
            )
        if table == "workout_sets":  # a set is final when done: its clock is its timestamp
            op.execute('UPDATE workout_sets SET updated_at = "timestamp"')
        if unique:
            op.create_index(unique[0], table, unique[1], unique=True)

    op.create_table(
        "catalog_meta",
        sa.Column("entity", sa.String(16), primary_key=True),
        sa.Column("version", sa.Integer(), nullable=False),
    )
    op.bulk_insert(
        sa.table("catalog_meta", sa.column("entity"), sa.column("version")),
        [{"entity": e, "version": 1} for e in CATALOG],
    )
    op.create_table(
        "sync_changes",
        sa.Column(
            "id", sa.BigInteger().with_variant(sa.Integer(), "sqlite"),
            primary_key=True, autoincrement=True,
        ),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("entity", sa.String(32), nullable=False),
        sa.Column("client_id", sa.String(64), nullable=False),
        sa.Column("op", sa.String(6), nullable=False),
        sa.Column("created_at", STAMP, nullable=False),
        sqlite_autoincrement=True,
    )
    op.create_index("ix_sync_changes_user_id_id", "sync_changes", ["user_id", "id"])
    op.create_table(
        "sync_ops",
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("op_id", sa.String(64), nullable=False),
        sa.Column("result", sa.JSON(), nullable=False),
        sa.Column("created_at", STAMP, nullable=False),
        sa.PrimaryKeyConstraint("user_id", "op_id"),
    )
    op.create_index("ix_sync_ops_created_at", "sync_ops", ["created_at"])

    for table, (entity, _fixed, _unique) in TABLES.items():  # parents first: plan before completions
        owner = (
            "FROM plan_completions t JOIN user_plans p ON p.id = t.user_plan_id"
            if table == "plan_completions"
            else f"FROM {table} t"
        )
        user = "p.user_id" if table == "plan_completions" else "t.user_id"
        bind.execute(
            sa.text(
                "INSERT INTO sync_changes (user_id, entity, client_id, op, created_at) "  # noqa: S608
                f"SELECT {user}, :e, t.client_id, 'upsert', :t {owner} ORDER BY t.id"
            ).bindparams(sa.bindparam("t", type_=STAMP)),
            {"e": entity, "t": now},
        )


def downgrade():
    op.drop_index("ix_sync_ops_created_at", table_name="sync_ops")
    op.drop_table("sync_ops")
    op.drop_index("ix_sync_changes_user_id_id", table_name="sync_changes")
    op.drop_table("sync_changes")
    op.drop_table("catalog_meta")
    _fk_off(off=True)
    for table, (_entity, _fixed, unique) in reversed(TABLES.items()):
        _drop_leftover(table)
        with op.batch_alter_table(table) as batch_op:
            if unique:
                batch_op.drop_index(unique[0])
            batch_op.drop_column("updated_at")
            batch_op.drop_column("client_id")
    _fk_off(off=False)
