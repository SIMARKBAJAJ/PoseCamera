"""Device-bound sessions: users.email(+verified_at), devices, otp_challenges, security_events,
auth_locks (SECURITY_DESIGN.md section 8).

Upgrade only ADDs two nullable columns to users and creates new tables: no rebuild, no FK-off
(as 0008). Downgrade drops the tables, then drops the users columns, which rebuilds users on SQLite
(every user table references it), so it uses the 0003 FK-off pattern; Postgres runs plain ALTERs.
users.email is written only when an OTP sent to it is verified; until then the address waits on
otp_challenges.email.
Downgrade loses device trust, pending OTPs, the audit log and emails (by nature of the revision).

Revision ID: 0009
Revises: 0008
"""
import sqlalchemy as sa
from alembic import op

revision = "0009"
down_revision = "0008"
branch_labels = None
depends_on = None

STAMP = sa.DateTime(timezone=True)
HASH = sa.String(64)


def upgrade():
    op.add_column("users", sa.Column("email", sa.String(254), nullable=True))
    op.add_column("users", sa.Column("email_verified_at", STAMP, nullable=True))
    op.create_index("ix_users_email", "users", ["email"])

    op.create_table(
        "devices",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id"),
                  nullable=False),  # fmt: skip
        sa.Column("device_id", sa.String(64), nullable=False),
        sa.Column("token_hash", HASH, nullable=False),
        sa.Column("prev_token_hash", HASH, nullable=True),
        sa.Column("rotation", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("sig_epoch", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("rotated_at", STAMP, nullable=False),
        sa.Column("created_at", STAMP, nullable=False),
        sa.Column("last_seen_at", STAMP, nullable=False),
        sa.Column("ip_hash", HASH, nullable=True),
        sa.Column("label", sa.String(128), nullable=True),
        sa.Column("revoked_at", STAMP, nullable=True),
        sa.Column("revoked_reason", sa.String(16), nullable=True),
        sa.CheckConstraint(
            "revoked_reason IN ('logout','logout_all','replay','admin')",
            name="ck_devices_revoked_reason",
        ),
        sqlite_autoincrement=True,  # device ids feed the signing key: never reused
    )
    op.create_index("ix_devices_user_id", "devices", ["user_id"])
    op.create_index("uq_devices_user_device", "devices", ["user_id", "device_id"], unique=True)

    op.create_table(
        "otp_challenges",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("user_id", sa.Integer(),
                  sa.ForeignKey("users.id"), nullable=False),  # fmt: skip
        sa.Column("device_id", sa.String(64), nullable=False),
        sa.Column("purpose", sa.String(16), nullable=False),
        sa.Column("code_hash", HASH, nullable=False),
        sa.Column("attempts", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("sent_count", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("created_at", STAMP, nullable=False),
        sa.Column("last_sent_at", STAMP, nullable=False),
        sa.Column("expires_at", STAMP, nullable=False),
        sa.Column("consumed_at", STAMP, nullable=True),
        sa.Column("ip_hash", HASH, nullable=True),
        sa.Column("email", sa.String(254), nullable=True),  # pending address (verify_email only)
        sa.CheckConstraint("purpose IN ('login','verify_email')", name="ck_otp_challenges_purpose"),
    )
    op.create_index("ix_otp_challenges_user_id", "otp_challenges", ["user_id"])

    op.create_table(
        "security_events",
        sa.Column(
            "id", sa.BigInteger().with_variant(sa.Integer(), "sqlite"),
            primary_key=True, autoincrement=True,
        ),  # fmt: skip
        sa.Column("created_at", STAMP, nullable=False),
        sa.Column("kind", sa.String(24), nullable=False),
        sa.Column("user_id", sa.Integer(), nullable=True),  # no FK: kept after account deletion
        sa.Column("account_key", HASH, nullable=True),
        sa.Column("ip_key", HASH, nullable=True),
        sa.Column("subnet_key", HASH, nullable=True),
        sa.Column("device_key", HASH, nullable=True),
        sa.Column("score", sa.Integer(), nullable=True),
        sa.Column("action", sa.String(16), nullable=True),
        sa.Column("detail", sa.JSON(), nullable=True),
        sqlite_autoincrement=True,
    )
    for name, cols in (
        ("ix_security_events_account", ["account_key", "kind", "created_at"]),
        ("ix_security_events_ip", ["ip_key", "kind", "created_at"]),
        ("ix_security_events_subnet", ["subnet_key", "kind", "created_at"]),
        ("ix_security_events_device", ["device_key", "created_at"]),
        ("ix_security_events_kind", ["kind", "created_at"]),
    ):
        op.create_index(name, "security_events", cols)

    op.create_table(
        "auth_locks",
        sa.Column("scope", sa.String(8), nullable=False),
        sa.Column("key", HASH, nullable=False),
        sa.Column("until", STAMP, nullable=False),
        sa.Column("level", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("reason", sa.String(24), nullable=True),
        sa.PrimaryKeyConstraint("scope", "key"),
        sa.CheckConstraint(
            "scope IN ('account','ip','subnet','device','global')", name="ck_auth_locks_scope"
        ),
    )


def _drop_leftover(table):  # leftover of a failed SQLite rebuild (see 0008)
    if op.get_bind().dialect.name == "sqlite":
        op.execute(f"DROP TABLE IF EXISTS _alembic_tmp_{table}")


def downgrade():
    op.drop_table("auth_locks")
    for name in ("account", "ip", "subnet", "device", "kind"):
        op.drop_index(f"ix_security_events_{name}", table_name="security_events")
    op.drop_table("security_events")
    op.drop_index("ix_otp_challenges_user_id", table_name="otp_challenges")
    op.drop_table("otp_challenges")
    op.drop_index("uq_devices_user_device", table_name="devices")
    op.drop_index("ix_devices_user_id", table_name="devices")
    op.drop_table("devices")
    op.drop_index("ix_users_email", table_name="users")

    # rebuilding users, which every user table references: FK off (see 0003)
    sqlite = op.get_bind().dialect.name == "sqlite"
    if sqlite:
        with op.get_context().autocommit_block():
            op.execute("PRAGMA foreign_keys=OFF")
    _drop_leftover("users")
    with op.batch_alter_table("users") as batch_op:
        batch_op.drop_column("email_verified_at")
        batch_op.drop_column("email")
    if sqlite:
        broken = op.get_bind().exec_driver_sql("PRAGMA foreign_key_check").fetchall()
        if broken:
            msg = f"foreign key violations after rebuilding users: {broken[:3]}"
            raise RuntimeError(msg)
        with op.get_context().autocommit_block():
            op.execute("PRAGMA foreign_keys=ON")
