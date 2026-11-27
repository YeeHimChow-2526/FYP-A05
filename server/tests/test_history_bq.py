"""History -> BigQuery adapter + readable layer. Fake BigQuery client only; no cloud calls."""

import csv
import io
import json
import unittest
from datetime import date, timedelta
from pathlib import Path

import sqlglot

from app import history as h
from app import history_bq as hb
from test_history import live_store
from test_ingest_td import NOW
from test_shared_cache import at

DAY = date(2026, 11, 5)


class FakeBQ:
    """Models BigQuery semantics used here: job ids unique forever; staging load replaces the
    staging table; script = atomic insert-only MERGE; `abort_next` simulates a transaction
    aborted by a concurrent publisher; `lose_ack` = job ran but the caller saw an error."""

    def __init__(self, store):
        self.store, self.jobs, self.tables = store, {}, {}
        self.abort_next = 0
        self.lose_ack = False
        self.pause_merge = False  # leave merge RUNNING (job outlives any caller lease)
        self.submitted = []

    def get_job_state(self, job_id):
        return self.jobs.get(job_id)

    def _ret(self, job_id, state):
        self.jobs[job_id] = state
        if self.lose_ack:
            self.lose_ack = False
            return None  # caller does not learn the outcome
        return state

    def load_staging(self, *, job_id, uris, table, schema):
        assert job_id not in self.jobs
        self.submitted.append(job_id)
        rows = []
        for uri in uris:
            name = uri.split("/", 3)[3]
            rows += hb.load_ndjson([self.store.objects[name][0]])
        self.tables[table] = rows
        return self._ret(job_id, "DONE")

    def run_script(self, *, job_id, sql):
        assert job_id not in self.jobs
        self.submitted.append(job_id)
        if self.abort_next:
            self.abort_next -= 1
            return self._ret(job_id, "FAILED")
        if self.pause_merge:
            return self._ret(job_id, "RUNNING")
        target = sql.split("MERGE `")[1].split("`")[0]
        staging = sql.split("FROM `")[2].split("`")[0]
        day = sql.split("DATE '")[1].split("'")[0]
        existing = self.tables.setdefault(target, [])
        keys = {tuple(r[k] for k in hb.KEY) for r in existing}
        for r in self.tables[staging]:
            k = tuple(r[k] for k in hb.KEY)
            if r["snapshot_date"] == day and k not in keys:
                existing.append(r)
                keys.add(k)
        return self._ret(job_id, "DONE")

    def target(self, plan):
        return self.tables.get(plan.table, [])


def captured_store(*offsets):
    store = live_store()
    for minutes in offsets:
        h.capture_once(store, clock=at(NOW + timedelta(minutes=minutes)))
    return store


class SchemaTests(unittest.TestCase):
    def test_schema_matches_history_rows_exactly(self):
        store = captured_store(1)
        name = next(k for k in store.objects if k.startswith("history/"))
        row = json.loads(store.objects[name][0].decode().splitlines()[0])
        self.assertEqual(set(row), {f[0] for f in hb.FIELDS})
        for f in hb.bq_schema():
            self.assertTrue(f["description"])

    def test_view_sql_parses_as_bigquery_locally(self):
        sql = hb.view_sql("proj", "hkparking_history")
        sqlglot.parse_one(sql, read="bigquery")
        self.assertIn("Asia/Hong_Kong", sql)
        self.assertIn("`空位數`", sql)
        aliases = [x.split("`")[1] for x in sql.split(" AS ")[1:]]
        for name in aliases:  # flexible column names: no ( ) or full-width brackets
            self.assertFalse(set(name) & set("()（）!\"$"), name)
        self.assertIn("（未知代碼）", sql)

    def test_dictionary_codes_and_unknown_fallback(self):
        self.assertEqual(hb.translate("vacancy_status", "NO_COUNT"), "來源冇提供可用數字（唔知幾多位）")
        self.assertEqual(hb.translate("vacancy_status", "WEIRD"), "（未知代碼）WEIRD")
        self.assertEqual(hb.translate("ParkingMeterStatus", "NU"), "暫停使用")
        self.assertFalse(any("維修" in zh and "唔" not in zh for _, _, zh, _ in hb.DICTIONARY))


def keys(rows):
    return sorted(tuple(r[k] for k in hb.KEY) for r in rows)


class LoadTests(unittest.TestCase):
    def setUp(self):
        self.store = captured_store(1)
        self.a = hb.plan_load(self.store, "b", "proj", "ds", DAY)             # old batch (1 slot)
        h.capture_once(self.store, clock=at(NOW + timedelta(minutes=20)))   # late slot file
        self.b = hb.plan_load(self.store, "b", "proj", "ds", DAY)             # new batch (2 slots)
        self.bq = FakeBQ(self.store)

    def test_plan_deterministic_and_late_file_changes_batch(self):
        self.assertEqual(self.b, hb.plan_load(self.store, "b", "proj", "ds", DAY))
        self.assertEqual((len(self.a.uris), len(self.b.uris)), (1, 2))
        self.assertNotEqual(self.a.staging, self.b.staging)
        self.assertEqual(self.b.table, "proj.ds.grid_occupancy_history")

    def test_old_delayed_batch_after_new_cannot_regress_or_duplicate(self):
        self.assertEqual(hb.execute_load(self.b, self.bq), "loaded")
        after_b = keys(self.bq.target(self.b))
        self.assertEqual(hb.execute_load(self.a, self.bq), "loaded")  # A finishes late
        self.assertEqual(keys(self.bq.target(self.b)), after_b)
        self.assertEqual(len(after_b), len(set(after_b)))

    def test_old_then_late_file_adds_only_new_rows(self):
        hb.execute_load(self.a, self.bq)
        n_a = len(self.bq.target(self.a))
        hb.execute_load(self.b, self.bq)
        self.assertGreater(len(self.bq.target(self.b)), n_a)
        self.assertEqual(len(keys(self.bq.target(self.b))), len(set(keys(self.bq.target(self.b)))))

    def test_simultaneous_publish_abort_is_retried_with_new_job_id(self):
        self.bq.abort_next = 1
        self.assertEqual(hb.execute_load(self.b, self.bq), "loaded")
        self.assertIn(f"merge_{self.b.job_base}_1", self.bq.submitted)
        self.assertIn(f"merge_{self.b.job_base}_2", self.bq.submitted)
        hb.execute_load(self.a, self.bq)
        self.assertEqual(len(keys(self.bq.target(self.b))), len(set(keys(self.bq.target(self.b)))))

    def test_lost_ack_rerun_observes_job_and_does_not_resubmit(self):
        self.bq.lose_ack = True  # staging ran, ack lost
        hb.execute_load(self.b, self.bq)
        self.assertEqual([j for j in self.bq.submitted if j.startswith("stg_")], [f"stg_{self.b.job_base}_1"])
        before = list(self.bq.submitted)
        n = len(self.bq.target(self.b))
        self.assertEqual(hb.execute_load(self.b, self.bq), "loaded")
        self.assertEqual(self.bq.submitted, before)  # nothing resubmitted
        self.assertEqual(len(self.bq.target(self.b)), n)

    def test_running_job_is_not_released_or_taken_over(self):
        self.bq.pause_merge = True
        self.assertEqual(hb.execute_load(self.b, self.bq), "in_progress")
        self.assertEqual(hb.execute_load(self.b, self.bq), "in_progress")  # long after any lease
        self.assertEqual([j for j in self.bq.submitted if j.startswith("merge_")], [f"merge_{self.b.job_base}_1"])

    def test_bounded_failures(self):
        self.bq.abort_next = 99
        self.assertEqual(hb.execute_load(self.b, self.bq), "failed")
        self.assertEqual(len([j for j in self.bq.submitted if j.startswith("merge_")]), hb.MAX_ATTEMPTS)

    def test_sql_shape_insert_only_with_lock(self):
        sql = hb.merge_sql(self.b)
        for stmt in sqlglot.parse(sql, read="bigquery"):
            self.assertIsNotNone(stmt)
        self.assertIn("DELETE FROM `proj.ds.grid_occupancy_history` WHERE FALSE", sql)
        self.assertIn("WHEN NOT MATCHED THEN INSERT ROW", sql)
        self.assertNotIn("WHEN MATCHED", sql)
        self.assertNotIn("TRUNCATE", sql)
        sqlglot.parse_one(hb.table_ddl(self.b.table), read="bigquery")

    def test_empty_date_and_demo_separation(self):
        self.assertEqual(hb.execute_load(hb.plan_load(self.store, "b", "p", "d", date(2026, 1, 1)), self.bq),
                         "nothing_to_load")
        demo = hb.plan_load(self.store, "b", "p", "d", DAY, demo=True)
        self.assertEqual((demo.table, demo.uris), ("p.d.grid_occupancy_history_demo", []))


class CsvTests(unittest.TestCase):
    def test_csv_bom_bilingual_hkt_and_null_blank(self):
        store = captured_store(1)
        rows = hb.load_ndjson(v for k, (v, _) in store.objects.items() if k.startswith("history/"))
        headers, lines = hb.readable_rows(rows)
        data = hb.to_csv_bytes(headers, lines)
        self.assertTrue(data.startswith(b"\xef\xbb\xbf"))
        table = list(csv.reader(io.StringIO(data.decode("utf-8-sig"))))
        col = {name: i for i, name in enumerate(table[0])}
        by = {r[col["地點編號（原始編號） / grid_id"]]: r for r in table[1:]}
        a, flag = by["tdc_a_fresh"], by["tdc_b_flag"]
        self.assertEqual(a[col["空位數（個車位） / vacant_count"]], "42")
        self.assertEqual(flag[col["空位數（個車位） / vacant_count"]], "")  # unknown blank, not 0
        self.assertEqual(flag[col["空位資料狀態（中文） / vacancy_status_zh"]], "來源冇提供可用數字（唔知幾多位）")
        self.assertEqual(a[col["來源更新時間（呢個地點）（香港時間） / record_source_updated_at_hkt"]], "2026-11-05 22:25:00")
        self.assertEqual(a[col["來源更新時間（呢個地點）（原始） / record_source_updated_at"]], "2026-11-05T22:25:00+08:00")
        self.assertTrue(hb.dictionary_csv_bytes().startswith(b"\xef\xbb\xbf"))

    def test_cli_export_writes_csv_and_dictionary(self):
        import tempfile

        store = captured_store(1)
        with tempfile.TemporaryDirectory() as d:
            nd = Path(d) / "slot.ndjson"
            nd.write_bytes(next(v for k, (v, _) in store.objects.items() if k.startswith("history/")))
            out = Path(d) / "history.csv"
            self.assertEqual(hb.main(["export-csv", "--out", str(out), "--ndjson", str(nd)]), 0)
            self.assertTrue(out.exists() and (Path(d) / "history_字典.csv").exists())

    def test_load_cli_defaults_to_plan_only(self):
        self.assertEqual(hb.main(["load", "--date", "2026-11-05"]), 2)  # no bucket/project: refuses


if __name__ == "__main__":
    unittest.main()
