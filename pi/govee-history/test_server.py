"""History store and HTTP checks. Run from this directory: python3 test_server.py"""

import json
import threading
import unittest
import urllib.error
import urllib.request

from server import HistoryApp, serve
from store import ROLL_15M_MS, ROLL_1D_MS, RAW_KEEP_MS, History


ADDRESS = "A1:B2:C3:D4:E5:F6"
LABEL = "Thermo-hygrometer"
NOW = 1_700_000_000_000


def reading(seen_at, temp, source="phone", address=ADDRESS, extra=""):
    return {
        "address": address,
        "name": "Govee_H5075_ABCD",
        "model": "H5075",
        "label": LABEL,
        "source": source,
        "seen_at": seen_at,
        "temp_c": temp,
        "humidity_pct": 45,
        "extra": extra,
    }


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.history = History(":memory:")

    def tearDown(self):
        self.history.close()

    def test_phone_unbound_is_stored_and_bound_is_dropped(self):
        other = "B1:B2:C3:D4:E5:F6"
        self.history.bind(ADDRESS, "H5075", "kitchen", NOW)
        result = self.history.ingest(
            [reading(NOW, 21.5), reading(NOW, 19.0, address=other)],
            "phone",
        )
        self.assertEqual(result["dropped_bound"], 1)
        self.assertEqual(result["stored"], 2)
        series = self.history.series(other, LABEL, "temp_c", "3m", NOW + 1000)
        self.assertEqual(len(series["points"]), 1)
        self.assertAlmostEqual(series["points"][0]["avg"], 19.0)
        bound_series = self.history.series(ADDRESS, LABEL, "temp_c", "3m", NOW + 1000)
        self.assertEqual(bound_series["points"], [])

    def test_gateway_replaces_phone_and_phone_does_not_replace_gateway(self):
        bucket = NOW - (NOW % 10_000)
        self.history.ingest([reading(bucket + 1000, 21.0)], "phone")
        self.history.ingest([reading(bucket + 2000, 22.0)], "gateway")
        self.history.ingest([reading(bucket + 3000, 19.0)], "phone")
        point = self.history.series(ADDRESS, LABEL, "temp_c", "30m", bucket + 10_000)["points"][0]
        self.assertAlmostEqual(point["avg"], 22.0)
        self.assertEqual(point["t"], bucket)

    def test_gateway_samples_in_one_bucket_keep_min_and_max(self):
        bucket = NOW - (NOW % 10_000)
        self.history.ingest([reading(bucket + 1000, 22.0)], "gateway")
        self.history.ingest([reading(bucket + 2000, 24.0)], "gateway")
        point = self.history.series(ADDRESS, LABEL, "temp_c", "3m", bucket + 5000)["points"][0]
        self.assertAlmostEqual(point["min"], 22.0)
        self.assertAlmostEqual(point["max"], 24.0)
        self.assertAlmostEqual(point["avg"], 23.0)

    def test_ranges_use_the_planned_windows(self):
        self.history.ingest([reading(NOW - 20_000, 21.0), reading(NOW - 200_000, 10.0)], "phone")
        short = self.history.series(ADDRESS, LABEL, "temp_c", "3m", NOW)
        self.assertEqual(short["bucket_ms"], 10_000)
        self.assertEqual([point["t"] for point in short["points"]], [NOW - 20_000 - ((NOW - 20_000) % 10_000)])
        half_hour = self.history.series(ADDRESS, LABEL, "temp_c", "30m", NOW)
        self.assertEqual(len(half_hour["points"]), 2)
        self.assertEqual(half_hour["bucket_ms"], 10_000)

    def test_three_day_rollup_and_raw_retention(self):
        recent = NOW - 60_000
        old = NOW - RAW_KEEP_MS - 60_000
        self.history.ingest([reading(recent, 21.5), reading(old, 18.0)], "phone")
        self.history.rollup(NOW)
        raw = self.history.db.execute("SELECT COUNT(*) AS n FROM raw").fetchone()["n"]
        self.assertEqual(raw, 2)
        rolled = self.history.db.execute(
            "SELECT bucket, v_min FROM roll_15m WHERE address = ? AND metric = 'temp_c' ORDER BY bucket",
            (ADDRESS,),
        ).fetchall()
        self.assertEqual(len(rolled), 2)
        self.assertEqual(rolled[0]["bucket"] % ROLL_15M_MS, 0)
        day = self.history.series(ADDRESS, LABEL, "temp_c", "3d", NOW)
        self.assertEqual(day["bucket_ms"], ROLL_15M_MS)
        self.assertGreaterEqual(len(day["points"]), 1)

    def test_raw_older_than_36_hours_is_deleted_after_rollup(self):
        old = NOW - RAW_KEEP_MS - 60_000
        self.history.ingest([reading(old, 18.0)], "phone")
        self.history.rollup(NOW)
        left = self.history.db.execute("SELECT COUNT(*) AS n FROM raw").fetchone()["n"]
        self.assertEqual(left, 0)
        kept = self.history.db.execute("SELECT COUNT(*) AS n FROM roll_15m").fetchone()["n"]
        self.assertEqual(kept, 2)

    def test_rollup_is_idempotent(self):
        self.history.ingest([reading(NOW - 1000, 21.0)], "phone")
        self.history.rollup(NOW)
        self.history.rollup(NOW)
        row = self.history.db.execute(
            "SELECT n, v_sum FROM roll_15m WHERE metric = 'temp_c'",
        ).fetchone()
        self.assertEqual(row["n"], 1)
        self.assertAlmostEqual(row["v_sum"], 21.0)

    def test_three_year_folds_days(self):
        three = 3 * ROLL_1D_MS
        base = (NOW // three) * three
        for offset, temp in ((0, 10.0), (ROLL_1D_MS, 20.0), (2 * ROLL_1D_MS, 30.0)):
            self.history.db.execute(
                """
                INSERT INTO roll_1d(address, label, metric, bucket, v_min, v_max, v_sum, n)
                VALUES(?, ?, 'temp_c', ?, ?, ?, ?, 1)
                """,
                (ADDRESS, LABEL, base + offset, temp, temp, temp),
            )
        outside = base - (1096 * ROLL_1D_MS) - ROLL_1D_MS
        self.history.db.execute(
            """
            INSERT INTO roll_1d(address, label, metric, bucket, v_min, v_max, v_sum, n)
            VALUES(?, ?, 'temp_c', ?, 1, 1, 1, 1)
            """,
            (ADDRESS, LABEL, outside),
        )
        self.history.db.commit()
        series = self.history.series(ADDRESS, LABEL, "temp_c", "3y", NOW)
        self.assertEqual(series["bucket_ms"], three)
        self.assertEqual(len(series["points"]), 1)
        self.assertAlmostEqual(series["points"][0]["avg"], 20.0)
        self.assertAlmostEqual(series["points"][0]["min"], 10.0)
        self.assertAlmostEqual(series["points"][0]["max"], 30.0)

    def test_one_year_is_daily(self):
        self.history.db.execute(
            """
            INSERT INTO roll_1d(address, label, metric, bucket, v_min, v_max, v_sum, n)
            VALUES(?, ?, 'temp_c', ?, 21, 22, 21.5, 1)
            """,
            (ADDRESS, LABEL, NOW - ROLL_1D_MS),
        )
        self.history.db.commit()
        series = self.history.series(ADDRESS, LABEL, "temp_c", "1y", NOW)
        self.assertEqual(series["bucket_ms"], ROLL_1D_MS)
        self.assertEqual(len(series["points"]), 1)

    def test_probe_metric_from_extra(self):
        self.history.ingest([reading(NOW, 21.0, extra="probe 1=54.0")], "phone")
        series = self.history.series(ADDRESS, LABEL, "probe_1", "3m", NOW + 1000)
        self.assertAlmostEqual(series["points"][0]["avg"], 54.0)

    def test_empty_reading_is_dropped(self):
        result = self.history.ingest(
            [{"address": ADDRESS, "label": LABEL, "seen_at": NOW}],
            "phone",
        )
        self.assertEqual(result["dropped_empty"], 1)
        self.assertEqual(result["stored"], 0)


class HttpTest(unittest.TestCase):
    def setUp(self):
        self.history = History(":memory:")
        self.token = "test-token"
        self.httpd = serve(self.history, self.token, "127.0.0.1", 0)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.history.close()

    def test_missing_token_is_rejected(self):
        opened = urllib.request.urlopen(self._url("/health"), timeout=3)
        self.assertEqual(opened.status, 200)
        opened.close()
        body = json.dumps({"readings": [reading(NOW, 21.5)]}).encode()
        request = urllib.request.Request(self._url("/v1/readings"), data=body)
        with self.assertRaises(urllib.error.HTTPError) as raised:
            urllib.request.urlopen(request, timeout=3)
        self.assertEqual(raised.exception.code, 401)

    def test_index_lists_a_sensor_without_a_token(self):
        self.history.ingest([reading(NOW, 21.5)], "phone", now_ms=NOW)
        request = urllib.request.Request(self._url("/"))
        with urllib.request.urlopen(request, timeout=3) as response:
            page = response.read().decode()
            self.assertIn("text/html", response.headers.get("Content-Type", ""))
        self.assertIn("Govee_H5075_ABCD", page)
        self.assertIn("21.5", page)

    def test_post_and_series_round_trip(self):
        body = json.dumps({"readings": [reading(NOW, 21.5)]}).encode()
        request = urllib.request.Request(
            self._url("/v1/readings"),
            data=body,
            headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=3) as response:
            payload = json.load(response)
        self.assertEqual(payload["stored"], 2)
        query = (
            f"/v1/series?address={ADDRESS}&label={LABEL.replace(' ', '%20')}"
            f"&metric=temp_c&range=3m"
        )
        series_request = urllib.request.Request(
            self._url(query),
            headers={"Authorization": f"Bearer {self.token}"},
        )
        # The fixed NOW in the reading may be outside the server's wall clock.
        # The in-process app is checked with an explicit clock below.
        app = HistoryApp(self.history, self.token)
        status, series = app.handle("GET", query, b"", f"Bearer {self.token}", now_ms=NOW + 1000)
        self.assertEqual(status, 200)
        self.assertAlmostEqual(series["points"][0]["avg"], 21.5)
        with urllib.request.urlopen(series_request, timeout=3) as response:
            self.assertEqual(response.status, 200)

    def test_phone_cannot_post_a_gateway_source(self):
        app = HistoryApp(self.history, self.token)
        body = json.dumps({"readings": [reading(NOW, 21.5, source="gateway")]}).encode()
        status, payload = app.handle("POST", "/v1/readings", body, f"Bearer {self.token}")
        self.assertEqual(status, 200)
        self.assertEqual(payload["stored"], 0)
        self.assertEqual(self.history.series(ADDRESS, LABEL, "temp_c", "3m", NOW + 1000)["points"], [])

    def test_bad_range_is_rejected(self):
        app = HistoryApp(self.history, self.token)
        status, payload = app.handle(
            "GET",
            f"/v1/series?address={ADDRESS}&label=Thermo-hygrometer&metric=temp_c&range=2d",
            b"",
            f"Bearer {self.token}",
        )
        self.assertEqual(status, 400)
        self.assertEqual(payload["error"], "bad range")

    def _url(self, path: str) -> str:
        return f"http://127.0.0.1:{self.port}{path}"


if __name__ == "__main__":
    unittest.main()
