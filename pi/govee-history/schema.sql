-- Graph store for the Pi. Buckets are unix milliseconds, floored.
-- raw is 10 seconds. Rollups are recomputed from the next finer table
-- before that finer table is trimmed, so a second run does not double-count.

CREATE TABLE IF NOT EXISTS raw (
    address TEXT NOT NULL,
    label TEXT NOT NULL,
    metric TEXT NOT NULL,
    bucket INTEGER NOT NULL,
    v_min REAL NOT NULL,
    v_max REAL NOT NULL,
    v_sum REAL NOT NULL,
    n INTEGER NOT NULL,
    source TEXT NOT NULL,
    PRIMARY KEY (address, label, metric, bucket)
);

CREATE TABLE IF NOT EXISTS roll_15m (
    address TEXT NOT NULL,
    label TEXT NOT NULL,
    metric TEXT NOT NULL,
    bucket INTEGER NOT NULL,
    v_min REAL NOT NULL,
    v_max REAL NOT NULL,
    v_sum REAL NOT NULL,
    n INTEGER NOT NULL,
    PRIMARY KEY (address, label, metric, bucket)
);

CREATE TABLE IF NOT EXISTS roll_6h (
    address TEXT NOT NULL,
    label TEXT NOT NULL,
    metric TEXT NOT NULL,
    bucket INTEGER NOT NULL,
    v_min REAL NOT NULL,
    v_max REAL NOT NULL,
    v_sum REAL NOT NULL,
    n INTEGER NOT NULL,
    PRIMARY KEY (address, label, metric, bucket)
);

CREATE TABLE IF NOT EXISTS roll_1d (
    address TEXT NOT NULL,
    label TEXT NOT NULL,
    metric TEXT NOT NULL,
    bucket INTEGER NOT NULL,
    v_min REAL NOT NULL,
    v_max REAL NOT NULL,
    v_sum REAL NOT NULL,
    n INTEGER NOT NULL,
    PRIMARY KEY (address, label, metric, bucket)
);

CREATE TABLE IF NOT EXISTS bound (
    address TEXT PRIMARY KEY,
    model TEXT,
    name TEXT,
    bound_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS candidates (
    address TEXT PRIMARY KEY,
    model TEXT,
    name TEXT,
    seen_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS latest (
    address TEXT NOT NULL,
    label TEXT NOT NULL,
    metric TEXT NOT NULL,
    seen_at INTEGER NOT NULL,
    value REAL NOT NULL,
    source TEXT NOT NULL,
    name TEXT,
    model TEXT,
    PRIMARY KEY (address, label, metric)
);

CREATE TABLE IF NOT EXISTS status (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS aliases (
    address TEXT PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS raw_bucket ON raw(bucket);
CREATE INDEX IF NOT EXISTS roll_15m_bucket ON roll_15m(bucket);
CREATE INDEX IF NOT EXISTS roll_6h_bucket ON roll_6h(bucket);
CREATE INDEX IF NOT EXISTS roll_1d_bucket ON roll_1d(bucket);
