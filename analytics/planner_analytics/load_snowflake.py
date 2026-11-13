"""Runs snowflake/01_setup.sql, 02_load.sql and 03_views.sql (idempotent; MERGE-based, so re-runs never duplicate rows).

Environment (nothing is hard-coded or read from files in the repo):
  SNOWFLAKE_ACCOUNT, SNOWFLAKE_USER, SNOWFLAKE_WAREHOUSE, SNOWFLAKE_ROLE (optional)
  SNOWFLAKE_PASSWORD  or  SNOWFLAKE_PRIVATE_KEY_PATH (+ SNOWFLAKE_PRIVATE_KEY_PASSPHRASE)
  S3_BUCKET, S3_PREFIX (default planner/), AWS_ROLE_ARN   -> substituted into the SQL placeholders
  RUN_ID (optional)   -> recorded in LOAD_AUDIT
Requires: pip install snowflake-connector-python
"""
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

SQL_DIR = Path(__file__).resolve().parents[2] / "snowflake"
FILES = ["01_setup.sql", "02_load.sql", "03_views.sql"]
PLACEHOLDER = re.compile(r"\{\{([A-Z0-9_]+)\}\}")


def render(sql, values):
    def sub(m):
        key = m.group(1)
        if not values.get(key):
            raise KeyError(f"{key} is required (set it in the environment)")
        return values[key]
    return PLACEHOLDER.sub(sub, sql)


def split_statements(sql):
    """Splits on ';' outside quotes and -- comments."""
    out, cur, i, n = [], [], 0, len(sql)
    while i < n:
        c = sql[i]
        if c == "'":
            j = i + 1
            while j < n and not (sql[j] == "'" and (j + 1 >= n or sql[j + 1] != "'")):
                j += 2 if sql[j] == "'" else 1
            cur.append(sql[i:j + 1])
            i = j + 1
        elif sql.startswith("--", i):
            j = sql.find("\n", i)
            i = n if j < 0 else j + 1
        elif c == ";":
            stmt = "".join(cur).strip()
            if stmt:
                out.append(stmt)
            cur = []
            i += 1
        else:
            cur.append(c)
            i += 1
    tail = "".join(cur).strip()
    if tail:
        out.append(tail)
    return out


def values_from_env():
    prefix = os.environ.get("S3_PREFIX", "planner/")
    return {
        "S3_BUCKET": os.environ.get("S3_BUCKET", ""),
        "S3_PREFIX": prefix if prefix.endswith("/") else prefix + "/",
        "AWS_ROLE_ARN": os.environ.get("AWS_ROLE_ARN", ""),
        "RUN_ID": os.environ.get("RUN_ID", datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")),
    }


def connect():
    import snowflake.connector  # optional dependency

    kwargs = dict(account=os.environ["SNOWFLAKE_ACCOUNT"], user=os.environ["SNOWFLAKE_USER"],
                  warehouse=os.environ["SNOWFLAKE_WAREHOUSE"])
    if os.environ.get("SNOWFLAKE_ROLE"):
        kwargs["role"] = os.environ["SNOWFLAKE_ROLE"]
    key_path = os.environ.get("SNOWFLAKE_PRIVATE_KEY_PATH")
    if key_path:
        from cryptography.hazmat.primitives import serialization

        pw = os.environ.get("SNOWFLAKE_PRIVATE_KEY_PASSPHRASE")
        key = serialization.load_pem_private_key(Path(key_path).read_bytes(), password=pw.encode() if pw else None)
        kwargs["private_key"] = key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
                                                  serialization.NoEncryption())
    else:
        kwargs["password"] = os.environ["SNOWFLAKE_PASSWORD"]
    return snowflake.connector.connect(**kwargs)


def main(argv=None):
    only = (argv or sys.argv[1:]) or FILES
    values = values_from_env()
    statements = []
    for name in only:
        statements += [(name, s) for s in split_statements(render((SQL_DIR / name).read_text(), values))]
    conn = connect()
    try:
        cur = conn.cursor()
        for i, (name, stmt) in enumerate(statements, 1):
            cur.execute(stmt)
        print(f"ran {len(statements)} statements from {', '.join(only)} (run {values['RUN_ID']})")
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
