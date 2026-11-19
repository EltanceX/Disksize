"""Compare all pre-migration columns in stopped-app SQLite backups, including raw BLOBs."""
import argparse
import hashlib
import json
import sqlite3
from pathlib import Path


def compare(before: Path, after: Path) -> dict:
    result = {}
    with sqlite3.connect(before) as old, sqlite3.connect(after) as new:
        assert new.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert not list(new.execute("PRAGMA foreign_key_check"))
        tables = [r[0] for r in old.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
        for table in tables:
            assert table.replace("_", "").isalnum()
            columns = list(old.execute(f'PRAGMA table_info("{table}")'))
            names = [r[1] for r in columns]
            keys = [r[1] for r in sorted(columns, key=lambda r: r[5]) if r[5]] or names
            selection = ",".join(f'"{name}"' for name in names)
            order = ",".join(f'"{name}"' for name in keys)

            def digest(connection):
                h, count = hashlib.sha256(), 0
                for row in connection.execute(f'SELECT {selection} FROM "{table}" ORDER BY {order}'):
                    values = [dict(blob=v.hex()) if isinstance(v, bytes) else v for v in row]
                    h.update(json.dumps(values, ensure_ascii=True, separators=(",", ":")).encode())
                    h.update(b"\n")
                    count += 1
                return dict(rows=count, sha256=h.hexdigest())

            a, b = digest(old), digest(new)
            assert a == b, f"Existing data changed in {table}: {a} != {b}"
            result[table] = a
        return dict(preserved=True, oldSchema=old.execute("PRAGMA user_version").fetchone()[0],
                    newSchema=new.execute("PRAGMA user_version").fetchone()[0], tables=result)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    value = compare(args.before, args.after)
    args.output.write_text(json.dumps(value, indent=2), encoding="utf-8")
    print(json.dumps(value))
