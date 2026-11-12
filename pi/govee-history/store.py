"""SQLite history for the six graph ranges.

Phone posts are stored only for addresses that are not bound to the H5151.
Gateway samples replace a bucket. Rollups are recomputed from the finer
table, then the finer rows past retention are deleted.
"""

from __future__ import annotations

import re
import sqlite3
import time
from pathlib import Path

SCHEMA = Path(__file__).with_name("schema.sql").read_text(encoding="utf-8")

RAW_MS = 10_000
ROLL_15M_MS = 15 * 60 * 1000
ROLL_6H_MS = 6 * 60 * 60 * 1000
ROLL_1D_MS = 24 * 60 * 60 * 1000
ROLL_3D_MS = 3 * ROLL_1D_MS

RAW_KEEP_MS = 36 * 60 * 60 * 1000
ROLL_15M_KEEP_MS = 100 * ROLL_1D_MS
ROLL_6H_KEEP_MS = 400 * ROLL_1D_MS
ROLL_1D_KEEP_MS = (3 * 365 + 40) * ROLL_1D_MS

RANGES = {
    "3m": {"window_ms": 180 * 1000, "bucket_ms": RAW_MS, "table": "raw"},
    "30m": {"window_ms": 30 * 60 * 1000, "bucket_ms": RAW_MS, "table": "raw"},
    "3d": {"window_ms": 3 * ROLL_1D_MS, "bucket_ms": ROLL_15M_MS, "table": "roll_15m"},
    "3mo": {"window_ms": 92 * ROLL_1D_MS, "bucket_ms": ROLL_6H_MS, "table": "roll_6h"},
    "1y": {"window_ms": 366 * ROLL_1D_MS, "bucket_ms": ROLL_1D_MS, "table": "roll_1d"},
    "3y": {"window_ms": 1096 * ROLL_1D_MS, "bucket_ms": ROLL_3D_MS, "table": "roll_1d"},
}

METRICS = ("temp_c", "humidity_pct", "battery_pct", "co2_ppm", "pm25")
PROBE = re.compile(r"probe (\d+)=(-?\d+(?:\.\d+)?)")
VERSION = "0.2.1"


def floor_bucket(timestamp_ms: int, size_ms: int) -> int:
    return timestamp_ms - (timestamp_ms % size_ms)


class History:
    def __init__(self, path: str) -> None:
        self.path = path
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode = WAL")
        self.db.execute("PRAGMA busy_timeout = 5000")
        self.db.executescript(SCHEMA)

    def close(self) -> None:
        self.db.close()

    def set_status(self, key: str, value: str) -> None:
        self.db.execute(
            "INSERT INTO status(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, value),
        )
        self.db.commit()

    def status_map(self) -> dict[str, str]:
        rows = self.db.execute("SELECT key, value FROM status").fetchall()
        return {row["key"]: row["value"] for row in rows}

    def bind(self, address: str, model: str, name: str, bound_at: int) -> None:
        self.db.execute(
            """
            INSERT INTO bound(address, model, name, bound_at) VALUES(?, ?, ?, ?)
            ON CONFLICT(address) DO UPDATE SET
                model = excluded.model,
                name = excluded.name
            """,
            (address, model, name, bound_at),
        )
        self.db.commit()

    def bound(self) -> list[dict]:
        rows = self.db.execute(
            "SELECT address, model, name, bound_at FROM bound ORDER BY address",
        ).fetchall()
        return [dict(row) for row in rows]

    def bound_addresses(self) -> set[str]:
        return {row["address"] for row in self.bound()}

    def note_candidate(self, address: str, model: str, name: str, seen_at: int) -> None:
        if address in self.bound_addresses():
            return
        self.db.execute(
            """
            INSERT INTO candidates(address, model, name, seen_at) VALUES(?, ?, ?, ?)
            ON CONFLICT(address) DO UPDATE SET
                model = excluded.model,
                name = excluded.name,
                seen_at = excluded.seen_at
            """,
            (address, model, name, seen_at),
        )
        self.db.commit()

    def candidates(self) -> list[dict]:
        rows = self.db.execute(
            "SELECT address, model, name, seen_at FROM candidates ORDER BY address",
        ).fetchall()
        return [dict(row) for row in rows]

    def ingest(self, readings: list[dict], source: str, now_ms: int | None = None) -> dict:
        if source not in ("phone", "gateway"):
            raise ValueError("source")
        bound = self.bound_addresses()
        stored = 0
        dropped_bound = 0
        dropped_empty = 0
        for reading in readings:
            address = str(reading.get("address") or "").strip()
            if not address:
                dropped_empty += 1
                continue
            if source == "phone" and address in bound:
                dropped_bound += 1
                continue
            seen_at = int(reading.get("seen_at") or now_ms or int(time.time() * 1000))
            points = metrics_of(reading)
            if not points:
                dropped_empty += 1
                continue
            label = str(reading.get("label") or "Sensor")
            name = reading.get("name") or ""
            model = reading.get("model") or ""
            bucket = floor_bucket(seen_at, RAW_MS)
            for metric, value in points:
                if self._put_raw(address, label, metric, bucket, float(value), source):
                    stored += 1
                self._put_latest(address, label, metric, seen_at, float(value), source, name, model)
        self.db.commit()
        return {"stored": stored, "dropped_bound": dropped_bound, "dropped_empty": dropped_empty}

    def latest(self) -> list[dict]:
        rows = self.db.execute(
            """
            SELECT address, label, metric, seen_at, value, source, name, model
            FROM latest
            ORDER BY address, label, metric
            """,
        ).fetchall()
        grouped: dict[tuple[str, str], dict] = {}
        for row in rows:
            key = (row["address"], row["label"])
            item = grouped.get(key)
            if item is None:
                item = {
                    "address": row["address"],
                    "label": row["label"],
                    "name": "",
                    "model": "",
                    "seen_at": 0,
                    "source": row["source"],
                }
                grouped[key] = item
            item[row["metric"]] = row["value"]
            if row["name"]:
                item["name"] = row["name"]
            if row["model"]:
                item["model"] = row["model"]
            if row["seen_at"] >= item["seen_at"]:
                item["seen_at"] = row["seen_at"]
                item["source"] = row["source"]
        return list(grouped.values())

    def series(self, address: str, label: str, metric: str, range_name: str, now_ms: int) -> dict:
        spec = RANGES[range_name]
        start = now_ms - spec["window_ms"]
        table = spec["table"]
        rows = self.db.execute(
            f"""
            SELECT bucket, v_min, v_max, v_sum, n
            FROM {table}
            WHERE address = ? AND label = ? AND metric = ? AND bucket >= ?
            ORDER BY bucket
            """,
            (address, label, metric, start),
        ).fetchall()
        if range_name == "3y":
            points = self._fold(rows, spec["bucket_ms"])
        else:
            points = [_point(row["bucket"], row["v_min"], row["v_max"], row["v_sum"], row["n"]) for row in rows]
        return {"range": range_name, "bucket_ms": spec["bucket_ms"], "points": points}

    def rollup(self, now_ms: int) -> None:
        self._recompute("roll_15m", "raw", ROLL_15M_MS)
        self._recompute("roll_6h", "roll_15m", ROLL_6H_MS)
        self._recompute("roll_1d", "roll_6h", ROLL_1D_MS)
        self.db.execute("DELETE FROM raw WHERE bucket < ?", (now_ms - RAW_KEEP_MS,))
        self.db.execute("DELETE FROM roll_15m WHERE bucket < ?", (now_ms - ROLL_15M_KEEP_MS,))
        self.db.execute("DELETE FROM roll_6h WHERE bucket < ?", (now_ms - ROLL_6H_KEEP_MS,))
        self.db.execute("DELETE FROM roll_1d WHERE bucket < ?", (now_ms - ROLL_1D_KEEP_MS,))
        self.db.commit()

    def health(self) -> dict:
        last = self.db.execute("SELECT MAX(seen_at) AS seen_at FROM latest").fetchone()
        db_bytes = 0
        if self.path != ":memory:":
            for suffix in ("", "-wal", "-shm"):
                file = Path(self.path + suffix)
                if file.exists():
                    db_bytes += file.stat().st_size
        status = self.status_map()
        return {
            "ok": True,
            "version": VERSION,
            "db_bytes": db_bytes,
            "last_ingest_ms": last["seen_at"] if last and last["seen_at"] is not None else None,
            "gateway": status.get("gateway", "down"),
            "bound": len(self.bound_addresses()),
        }

    def _put_raw(self, address, label, metric, bucket, value, source) -> bool:
        row = self.db.execute(
            """
            SELECT source, v_min, v_max, v_sum, n FROM raw
            WHERE address = ? AND label = ? AND metric = ? AND bucket = ?
            """,
            (address, label, metric, bucket),
        ).fetchone()
        if row is None:
            self.db.execute(
                """
                INSERT INTO raw(address, label, metric, bucket, v_min, v_max, v_sum, n, source)
                VALUES(?, ?, ?, ?, ?, ?, ?, 1, ?)
                """,
                (address, label, metric, bucket, value, value, value, source),
            )
            return True
        if source == "phone":
            return False
        if row["source"] == "gateway":
            self.db.execute(
                """
                UPDATE raw
                SET v_min = MIN(v_min, ?), v_max = MAX(v_max, ?), v_sum = v_sum + ?, n = n + 1, source = 'gateway'
                WHERE address = ? AND label = ? AND metric = ? AND bucket = ?
                """,
                (value, value, value, address, label, metric, bucket),
            )
            return True
        self.db.execute(
            """
            UPDATE raw
            SET v_min = ?, v_max = ?, v_sum = ?, n = 1, source = 'gateway'
            WHERE address = ? AND label = ? AND metric = ? AND bucket = ?
            """,
            (value, value, value, address, label, metric, bucket),
        )
        return True

    def _put_latest(self, address, label, metric, seen_at, value, source, name, model) -> None:
        row = self.db.execute(
            "SELECT seen_at, source FROM latest WHERE address = ? AND label = ? AND metric = ?",
            (address, label, metric),
        ).fetchone()
        if row is not None:
            if seen_at < row["seen_at"]:
                return
            if seen_at == row["seen_at"] and source == "phone" and row["source"] == "gateway":
                return
        self.db.execute(
            """
            INSERT INTO latest(address, label, metric, seen_at, value, source, name, model)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(address, label, metric) DO UPDATE SET
                seen_at = excluded.seen_at,
                value = excluded.value,
                source = excluded.source,
                name = excluded.name,
                model = excluded.model
            """,
            (address, label, metric, seen_at, value, source, name, model),
        )

    def _recompute(self, dest: str, source: str, size_ms: int) -> None:
        rows = self.db.execute(
            f"""
            SELECT address, label, metric,
                   (bucket / ?) * ? AS bucket,
                   MIN(v_min) AS v_min,
                   MAX(v_max) AS v_max,
                   SUM(v_sum) AS v_sum,
                   SUM(n) AS n
            FROM {source}
            GROUP BY address, label, metric, (bucket / ?) * ?
            """,
            (size_ms, size_ms, size_ms, size_ms),
        ).fetchall()
        for row in rows:
            self.db.execute(
                f"""
                INSERT INTO {dest}(address, label, metric, bucket, v_min, v_max, v_sum, n)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(address, label, metric, bucket) DO UPDATE SET
                    v_min = excluded.v_min,
                    v_max = excluded.v_max,
                    v_sum = excluded.v_sum,
                    n = excluded.n
                """,
                (
                    row["address"],
                    row["label"],
                    row["metric"],
                    row["bucket"],
                    row["v_min"],
                    row["v_max"],
                    row["v_sum"],
                    row["n"],
                ),
            )

    @staticmethod
    def _fold(rows, size_ms: int) -> list[dict]:
        folded: dict[int, list[float]] = {}
        order: list[int] = []
        for row in rows:
            bucket = floor_bucket(int(row["bucket"]), size_ms)
            if bucket not in folded:
                folded[bucket] = [row["v_min"], row["v_max"], row["v_sum"], row["n"]]
                order.append(bucket)
                continue
            cur = folded[bucket]
            cur[0] = min(cur[0], row["v_min"])
            cur[1] = max(cur[1], row["v_max"])
            cur[2] += row["v_sum"]
            cur[3] += row["n"]
        return [_point(bucket, *folded[bucket]) for bucket in order]


def metrics_of(reading: dict) -> list[tuple[str, float]]:
    points: list[tuple[str, float]] = []
    for metric in METRICS:
        value = reading.get(metric)
        if value is None or value == "":
            continue
        points.append((metric, float(value)))
    extra = reading.get("extra") or ""
    for match in PROBE.finditer(str(extra)):
        points.append((f"probe_{match.group(1)}", float(match.group(2))))
    probes = reading.get("probes") or []
    if isinstance(probes, list):
        for probe in probes:
            if not isinstance(probe, dict):
                continue
            ident = probe.get("id")
            value = probe.get("temperature_c")
            if ident is None or value is None:
                continue
            points.append((f"probe_{int(ident)}", float(value)))
    return points


def _point(bucket: int, v_min: float, v_max: float, v_sum: float, n: int) -> dict:
    count = int(n) if int(n) else 1
    return {
        "t": int(bucket),
        "min": v_min,
        "max": v_max,
        "avg": v_sum / count,
    }
