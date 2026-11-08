"""History (GCS NDJSON, app/history.py) -> BigQuery daily load + readable layer + CSV export.

Default is PLAN ONLY (no BigQuery call, no write). `--execute` is required to submit a load job.
Nothing here is scheduled, deployed or connected; tests use a fake BigQuery client.

Load model (ARCHITECTURE §13, adapted to stay correct under concurrency):
1. stage: load the date's listed slot objects into an immutable-per-batch staging table
   stg_{table}_{YYYYMMDD}_{fingerprint} (fingerprint = sha256 of object names@generations;
   same batch => same content, so WRITE_TRUNCATE of staging is idempotent; auto-expires 7 days).
2. publish: one transaction {no-op DELETE (takes the documented table lock); insert-only MERGE
   on key (region, grid_id, slot_start, record_type)}. Never UPDATE/DELETE/TRUNCATE the target.
Why this is safe without a lease/token (BigQuery does not participate in fencing): History objects
are write-once (generation 0), so a key's row content never changes; any batch is a subset of the
eventual object set. An old/delayed batch publishing after a newer one can only insert keys that
are still absent — it cannot remove or overwrite. Concurrent publishers: one aborts and is retried.
Lost ack: deterministic job ids are read back first (DONE/RUNNING are observed, never resubmitted);
FAILED ids are not reusable in BigQuery, so the next suffix is used (max MAX_ATTEMPTS).
Not claimed: exactly-once jobs, or completeness of a date — a file not yet written when listed is
simply absent until a later rerun adds it. Demo -> separate *_demo table only with --demo.

Readable layer: BigQuery column descriptions + view SQL with Traditional Chinese names, units and
HKT times (raw UTC/ISO columns kept), and a UTF-8-BOM CSV export with bilingual headers and a
code dictionary for opening in Excel without BigQuery.
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
from dataclasses import dataclass, field
from datetime import date, datetime
from typing import Any, Callable, Iterable, Protocol
from zoneinfo import ZoneInfo

from .history import HISTORY_DEMO_PREFIX, HISTORY_PREFIX

HKT = ZoneInfo("Asia/Hong_Kong")
TABLE = "grid_occupancy_history"
DEMO_TABLE = "grid_occupancy_history_demo"
MAX_ATTEMPTS = 3  # distinct job ids per (date, fingerprint) after FAILED jobs

# (field, BQ type, mode, 中文名, unit, description). Source of truth for schema + CSV headers.
FIELDS: list[tuple[str, str, str, str, str, str]] = [
    ("snapshot_date", "DATE", "REQUIRED", "日期（香港）", "日期", "記錄所屬嘅香港日期；分區欄"),
    ("slot_start", "TIMESTAMP", "REQUIRED", "記錄時段開始", "時間（香港時間顯示）", "取樣時段開始（07:30–09:30／17:30–20:00 每5分鐘；01:00–06:00 每60分鐘；其餘每15分鐘）"),
    ("slot_interval_minutes", "INT64", "REQUIRED", "時段長度", "分鐘", "今次取樣時段長度"),
    ("record_type", "STRING", "REQUIRED", "記錄類型", "代碼", "PERIODIC＝定時取樣（暫時只有呢種）"),
    ("region", "STRING", "REQUIRED", "資料區", "代碼", "例如 HK_TD＝運輸署停車場；HK_TD_METERS＝運輸署咪錶"),
    ("source", "STRING", "NULLABLE", "資料來源", "代碼", "例如 TD_DATA_GOV_HK"),
    ("grid_id", "STRING", "REQUIRED", "地點編號", "原始編號", "停車場＝運輸署 park_id；咪錶＝本系統按街段組合嘅編號"),
    ("name", "STRING", "NULLABLE", "地點名稱", "文字", "來源提供嘅名稱（停車場 name_tc；咪錶＝街道＋路段）"),
    ("entity_type", "STRING", "REQUIRED", "地點類型", "代碼", "CARPARK＝停車場；METER_GRID＝咪錶組"),
    ("vacant_count", "INT64", "NULLABLE", "空位數", "個車位", "來源有實數先有值；空白＝唔知，唔係 0；0＝來源話冇位"),
    ("vacancy_status", "STRING", "REQUIRED", "空位資料狀態", "代碼", "見字典：COUNT／NO_COUNT／SOURCE_STALE／CLOSED／NOT_FOR_USE／UNKNOWN"),
    ("vacancy_reason", "STRING", "NULLABLE", "狀態細節", "代碼", "見字典，例如 A_COUNT、B_FLAG_NO_COUNT"),
    ("is_open", "BOOL", "REQUIRED", "開放中", "是／否", "來源話關閉＝否；咪錶未計營業時間（一律當開放）"),
    ("source_status", "STRING", "REQUIRED", "來源整體狀態", "代碼", "ok＝正常；degraded＝來源有問題或過細"),
    ("source_snapshot_ts", "TIMESTAMP", "REQUIRED", "來源發佈時間（整批）", "時間", "政府數據嗰批嘅發佈時間（唔係我哋抓取時間）"),
    ("record_source_updated_at", "TIMESTAMP", "REQUIRED", "來源更新時間（呢個地點）", "時間", "政府數據入面呢個地點自己嘅更新時間"),
    ("record_source_age_seconds", "INT64", "REQUIRED", "資料年齡（呢個地點）", "秒", "記錄當刻距離呢個地點更新時間"),
    ("snapshot_source_age_seconds", "INT64", "REQUIRED", "資料年齡（整批）", "秒", "記錄當刻距離整批發佈時間"),
    ("checked_at", "TIMESTAMP", "NULLABLE", "最後成功檢查來源", "時間", "我哋最後一次成功問過政府（有冇新資料都計）"),
    ("processed_at", "TIMESTAMP", "REQUIRED", "系統整理時間", "時間", "即時快照整理好嘅時間"),
    ("captured_at", "TIMESTAMP", "REQUIRED", "歷史記錄時間", "時間", "歷史記錄讀取快照嘅時間"),
]
TIMESTAMP_FIELDS = [f[0] for f in FIELDS if f[1] == "TIMESTAMP"]

# Code dictionary: (field, code, 中文, provenance). Unknown codes are shown raw.
TD_METER_SPEC = ("TD data spec, Distribution of Metered Parking Spaces and Occupancy … "
                 "https://www.td.gov.hk/datagovhk_td/metered-parking-spaces-data/resources/en/dataspec/metered_parking_spaces_data_dataspec.pdf")
PROJECT = "本項目定義（cloud server/app/models.py VacancyStatus／ingest）"
TD_CARPARK_UNDOC = "本項目對運輸署停車場 vacancy_type 嘅處理；運輸署冇公開文件解釋 -1 或 B 旗號非 0"
DICTIONARY: list[tuple[str, str, str, str]] = [
    ("vacancy_status", "COUNT", "有實數", PROJECT),
    ("vacancy_status", "NO_COUNT", "來源冇提供可用數字（唔知幾多位）", PROJECT),
    ("vacancy_status", "SOURCE_STALE", "呢個地點嘅來源資料太舊（超過30分鐘），數字唔用", PROJECT),
    ("vacancy_status", "CLOSED", "來源話關閉", PROJECT),
    ("vacancy_status", "NOT_FOR_USE", "咪錶全部「暫停使用」(NU)", PROJECT + "；NU 定義見 " + TD_METER_SPEC),
    ("vacancy_status", "UNKNOWN", "冇狀態資料", PROJECT),
    ("vacancy_reason", "A_COUNT", "運輸署 vacancy_type A：有實數", TD_CARPARK_UNDOC),
    ("vacancy_reason", "B_FLAG_FULL", "運輸署 vacancy_type B 而值係 0：當滿", TD_CARPARK_UNDOC),
    ("vacancy_reason", "B_FLAG_NO_COUNT", "運輸署 vacancy_type B 而值唔係 0：冇數字", TD_CARPARK_UNDOC),
    ("vacancy_reason", "A_NEGATIVE_OR_MALFORMED", "運輸署給負數或格式錯：唔知（唔當維修）", TD_CARPARK_UNDOC),
    ("vacancy_reason", "C_CLOSED", "運輸署 vacancy_type C：關閉", TD_CARPARK_UNDOC),
    ("vacancy_reason", "LASTUPDATE_OVER_30_MIN", "運輸署 lastupdate 超過30分鐘", PROJECT),
    ("vacancy_reason", "LASTUPDATE_MISSING", "運輸署冇 lastupdate", PROJECT),
    ("vacancy_reason", "SENSOR_COUNT", "咪錶全部有感應器數據", PROJECT),
    ("vacancy_reason", "PARTIAL_SENSOR_COVERAGE", "咪錶只部分有感應器數據（空位數係下限）", PROJECT),
    ("vacancy_reason", "ALL_NU", "咪錶全部暫停使用", PROJECT),
    ("vacancy_reason", "NO_SENSOR_DATA", "咪錶冇感應器數據（舊式咪錶）", PROJECT),
    ("vacancy_reason", "UNRECOGNISED_STATUS", "咪錶狀態代碼唔認識", PROJECT),
    ("entity_type", "CARPARK", "停車場", PROJECT),
    ("entity_type", "METER_GRID", "咪錶組（同一街段）", PROJECT),
    ("source_status", "ok", "正常", PROJECT),
    ("source_status", "degraded", "來源有問題／資料不足", PROJECT),
    ("record_type", "PERIODIC", "定時取樣", PROJECT),
    ("ParkingMeterStatus", "N", "正常", TD_METER_SPEC),
    ("ParkingMeterStatus", "NU", "暫停使用", TD_METER_SPEC),
    ("OccupancyStatus", "V", "空置", TD_METER_SPEC),
    ("OccupancyStatus", "O", "已佔用", TD_METER_SPEC),
]
_LOOKUP = {(f, c): zh for f, c, zh, _ in DICTIONARY}
TRANSLATED = ["vacancy_status", "vacancy_reason", "entity_type", "source_status", "record_type"]


def translate(field_name: str, code: Any) -> str:
    if code is None:
        return ""
    return _LOOKUP.get((field_name, str(code)), f"（未知代碼）{code}")


def bq_schema() -> list[dict[str, str]]:
    return [{"name": n, "type": t, "mode": m, "description": f"{zh}（單位：{unit}）— {desc}"}
            for n, t, m, zh, unit, desc in FIELDS]


def view_sql(project: str, dataset: str, table: str = TABLE) -> str:
    """OPTIONAL readable view over the ASCII machine table: Chinese aliases (BigQuery flexible
    column names; parentheses/full-width brackets avoided), HKT times, translated codes next to raw
    codes. Only parsed locally with sqlglot — not proof BigQuery accepts it."""
    def case(field_name: str) -> str:
        whens = " ".join(f"WHEN '{c}' THEN '{zh}'" for f, c, zh, _ in DICTIONARY if f == field_name)
        return f"CASE {field_name} {whens} ELSE CONCAT('（未知代碼）', {field_name}) END"

    def alias(text: str) -> str:
        # Flexible column names allow letters/digits/_/space but not ( ) etc.; keep \\p{L}\\p{N}_ only.
        return "`" + "".join(ch if ch.isalnum() or ch == "_" else "_" for ch in text).strip("_") + "`"

    cols = []
    for n, t, _m, zh, _u, _d in FIELDS:
        if t == "TIMESTAMP":
            cols.append(f"FORMAT_DATETIME('%Y-%m-%d %H:%M:%S', DATETIME({n}, 'Asia/Hong_Kong')) AS {alias(zh + '_香港時間')}")
            cols.append(f"{n} AS {alias(zh + '_UTC原始')}")
        else:
            cols.append(f"{n} AS {alias(zh)}")
        if n in TRANSLATED:
            cols.append(f"{case(n)} AS {alias(zh + '_中文')}")
    body = ",\n  ".join(cols)
    return f"CREATE OR REPLACE VIEW `{project}.{dataset}.v_{table}_readable` AS\nSELECT\n  {body}\nFROM `{project}.{dataset}.{table}`"


# --------------------------------------------------------------------------- load

KEY = ("region", "grid_id", "slot_start", "record_type")  # one row per key: objects are immutable
STAGING_EXPIRATION_HOURS = 168  # staging tables auto-expire after 7 days


class BigQueryClient(Protocol):
    def get_job_state(self, job_id: str) -> str | None: ...  # None | RUNNING | DONE | FAILED
    def load_staging(self, *, job_id: str, uris: list[str], table: str, schema: list[dict]) -> str: ...
    def run_script(self, *, job_id: str, sql: str) -> str: ...


@dataclass
class LoadPlan:
    day: date
    table: str          # project.dataset.table (target, append-only via insert-only MERGE)
    staging: str        # project.dataset.stg_..._{date}_{fingerprint}, immutable per batch
    uris: list[str]
    fingerprint: str
    problems: list[str] = field(default_factory=list)

    @property
    def job_base(self) -> str:
        return f"{self.table.rsplit('.', 1)[1]}_{self.day:%Y%m%d}_{self.fingerprint}"


def plan_load(store: Any, bucket: str, project: str, dataset: str, day: date, *, demo: bool = False) -> LoadPlan:
    prefix = f"{HISTORY_DEMO_PREFIX if demo else HISTORY_PREFIX}/dt={day:%Y%m%d}/"
    objects = sorted(o for o in store.list(prefix) if o[0].endswith(".ndjson"))
    table = DEMO_TABLE if demo else TABLE
    digest = hashlib.sha256("\n".join(f"{n}@{g}" for n, g in objects).encode()).hexdigest()[:12]
    return LoadPlan(day, f"{project}.{dataset}.{table}", f"{project}.{dataset}.stg_{table}_{day:%Y%m%d}_{digest}",
                    [f"gs://{bucket.removeprefix('gs://')}/{n}" for n, _ in objects], digest,
                    [] if objects else ["no history objects for this HKT date"])


def table_ddl(table: str) -> str:
    cols = ",\n  ".join(f"{n} {t}{' NOT NULL' if m == 'REQUIRED' else ''} OPTIONS(description={json.dumps(d)})"
                        for n, t, m, d in ((f["name"], f["type"], f["mode"], f["description"]) for f in bq_schema()))
    return (f"CREATE TABLE IF NOT EXISTS `{table}` (\n  {cols}\n)\nPARTITION BY snapshot_date\n"
            f"CLUSTER BY region, grid_id")


def merge_sql(plan: LoadPlan) -> str:
    """Insert-only MERGE inside a transaction. The no-op DELETE takes BigQuery's table-level
    transaction lock (documented pattern) so two publishers cannot both insert the same new key:
    the second aborts ('concurrent update') and is retried; retry is safe because it only inserts
    keys still absent. Nothing is ever updated or deleted, so an older/smaller batch finishing
    late cannot remove or overwrite rows from a newer batch."""
    on = " AND ".join(f"T.{k} = S.{k}" for k in KEY)
    part = ", ".join(KEY)
    return (
        "BEGIN TRANSACTION;\n"
        f"DELETE FROM `{plan.table}` WHERE FALSE;\n"
        f"MERGE `{plan.table}` T USING (\n"
        f"  SELECT * FROM `{plan.staging}` WHERE snapshot_date = DATE '{plan.day:%Y-%m-%d}'\n"
        f"  QUALIFY ROW_NUMBER() OVER (PARTITION BY {part} ORDER BY captured_at) = 1\n"
        f") S\nON T.snapshot_date = DATE '{plan.day:%Y-%m-%d}' AND {on}\n"
        "WHEN NOT MATCHED THEN INSERT ROW;\n"
        "COMMIT TRANSACTION;"
    )


def _run_step(client: BigQueryClient, base: str, submit: Callable[[str], str]) -> str:
    """Bounded attempts with deterministic job ids (BigQuery job ids are never reusable, so a
    FAILED attempt moves to the next suffix; a DONE/RUNNING id is observed, never resubmitted)."""
    for attempt in range(1, MAX_ATTEMPTS + 1):
        job_id = f"{base}_{attempt}"
        state = client.get_job_state(job_id)
        if state in ("DONE", "RUNNING"):
            return state
        if state == "FAILED":
            continue
        result = submit(job_id) or client.get_job_state(job_id)  # lost ack: read back same id
        if result is None:
            return "RUNNING"  # outcome unknown: never submit another id for it now
        if result in ("DONE", "RUNNING"):
            return result
    return "FAILED"


def execute_load(plan: LoadPlan, client: BigQueryClient) -> str:
    """stage (immutable per batch) -> publish (insert-only MERGE in transaction).
    Returns loaded | in_progress | failed | nothing_to_load. Rerun is always safe."""
    if plan.problems:
        return "nothing_to_load"
    staged = _run_step(client, f"stg_{plan.job_base}", lambda j: client.load_staging(
        job_id=j, uris=plan.uris, table=plan.staging, schema=bq_schema()))
    if staged != "DONE":
        return "in_progress" if staged == "RUNNING" else "failed"
    merged = _run_step(client, f"merge_{plan.job_base}", lambda j: client.run_script(job_id=j, sql=merge_sql(plan)))
    return {"DONE": "loaded", "RUNNING": "in_progress"}.get(merged, "failed")


class GoogleBigQueryClient:  # pragma: no cover - real client, never exercised in tests
    def __init__(self, project: str, location: str) -> None:
        from google.cloud import bigquery

        self._bq = bigquery
        self._location = location  # dataset location; must match (not assumed here)
        self._client = bigquery.Client(project=project)

    def get_job_state(self, job_id: str) -> str | None:
        from google.api_core.exceptions import NotFound

        try:
            job = self._client.get_job(job_id, location=self._location)
        except NotFound:
            return None
        if job.state != "DONE":
            return "RUNNING"
        return "FAILED" if job.error_result else "DONE"

    def _wait(self, job: Any, job_id: str) -> str:
        try:
            job.result()
        except Exception:  # noqa: BLE001 - state is read back below
            pass
        return self.get_job_state(job_id) or "RUNNING"

    def load_staging(self, *, job_id: str, uris: list[str], table: str, schema: list[dict]) -> str:
        from datetime import UTC, timedelta

        from google.api_core.exceptions import Conflict

        bq = self._bq
        t = bq.Table(table, schema=[bq.SchemaField(f["name"], f["type"], mode=f["mode"], description=f["description"]) for f in schema])
        t.expires = datetime.now(UTC) + timedelta(hours=STAGING_EXPIRATION_HOURS)
        self._client.create_table(t, exists_ok=True)
        config = bq.LoadJobConfig(source_format=bq.SourceFormat.NEWLINE_DELIMITED_JSON,
                                  write_disposition=bq.WriteDisposition.WRITE_TRUNCATE,  # same fingerprint = same content
                                  schema=t.schema, ignore_unknown_values=False)
        try:
            job = self._client.load_table_from_uri(uris, table, job_id=job_id, job_config=config, location=self._location)
        except Conflict:
            return self.get_job_state(job_id) or "RUNNING"
        return self._wait(job, job_id)

    def run_script(self, *, job_id: str, sql: str) -> str:
        from google.api_core.exceptions import Conflict

        try:
            job = self._client.query(sql, job_id=job_id, location=self._location)
        except Conflict:
            return self.get_job_state(job_id) or "RUNNING"
        return self._wait(job, job_id)


# --------------------------------------------------------------------------- CSV

def readable_rows(rows: Iterable[dict[str, Any]]) -> tuple[list[str], list[list[str]]]:
    headers, out = [], []
    for n, t, _m, zh, unit, _d in FIELDS:
        if t == "TIMESTAMP":
            headers += [f"{zh}（香港時間） / {n}_hkt", f"{zh}（原始） / {n}"]
        else:
            headers.append(f"{zh}（{unit}） / {n}")
        if n in TRANSLATED:
            headers.append(f"{zh}（中文） / {n}_zh")
    for r in rows:
        line = []
        for n, t, *_ in FIELDS:
            v = r.get(n)
            if t == "TIMESTAMP":
                line.append(datetime.fromisoformat(v).astimezone(HKT).strftime("%Y-%m-%d %H:%M:%S") if v else "")
                line.append("" if v is None else str(v))
            elif t == "BOOL":
                line.append("" if v is None else ("是" if v else "否"))
            else:
                line.append("" if v is None else str(v))  # NULL stays blank, never 0
            if n in TRANSLATED:
                line.append(translate(n, v))
        out.append(line)
    return headers, out


def to_csv_bytes(headers: list[str], rows: list[list[str]]) -> bytes:
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(headers)
    w.writerows(rows)
    return ("\ufeff" + buf.getvalue()).encode("utf-8")


def dictionary_csv_bytes() -> bytes:
    rows = [[n, zh, unit, desc, "", "", ""] for n, _t, _m, zh, unit, desc in FIELDS]
    rows += [[f, "", "", "", c, zh, src] for f, c, zh, src in DICTIONARY]
    return to_csv_bytes(["欄位 / field", "中文名", "單位", "說明", "代碼 / code", "代碼意思", "出處 / provenance"], rows)


def load_ndjson(blobs: Iterable[bytes]) -> list[dict[str, Any]]:
    return [json.loads(line) for b in blobs for line in b.decode("utf-8").splitlines() if line.strip()]


def main(argv: list[str] | None = None) -> int:
    import argparse
    import os
    from pathlib import Path

    from .ingest.td_carpark import GcsObjectStore

    p = argparse.ArgumentParser(prog="python -m app.history_bq")
    sub = p.add_subparsers(dest="cmd", required=True)
    ld = sub.add_parser("load", help="plan (default) or --execute the daily BigQuery load")
    ld.add_argument("--date", required=True, help="HKT date YYYY-MM-DD")
    ld.add_argument("--bucket", default=os.environ.get("GCS_STATE_BUCKET", ""))
    ld.add_argument("--project", default=os.environ.get("PROJECT_ID", ""))
    ld.add_argument("--dataset", default=os.environ.get("BQ_DATASET", "hkparking_history"))
    ld.add_argument("--demo", action="store_true")
    ld.add_argument("--execute", action="store_true", help="actually submit the load job")
    ld.add_argument("--location", default=os.environ.get("BQ_LOCATION", ""), help="BigQuery dataset location")
    vw = sub.add_parser("view-sql")
    vw.add_argument("--project", required=True)
    vw.add_argument("--dataset", default="hkparking_history")
    ex = sub.add_parser("export-csv", help="readable UTF-8-BOM CSV from local NDJSON files or GCS (read-only)")
    ex.add_argument("--out", required=True)
    ex.add_argument("--ndjson", nargs="*", default=[])
    ex.add_argument("--bucket", default="")
    ex.add_argument("--date", default="")
    a = p.parse_args(argv)
    if a.cmd == "view-sql":
        print(table_ddl(f"{a.project}.{a.dataset}.{TABLE}") + ";\n\n" + view_sql(a.project, a.dataset))
        return 0
    if a.cmd == "export-csv":
        blobs = [Path(f).read_bytes() for f in a.ndjson]
        if a.bucket and a.date:
            store = GcsObjectStore(a.bucket)
            day = date.fromisoformat(a.date)
            for name, _ in store.list(f"{HISTORY_PREFIX}/dt={day:%Y%m%d}/"):
                blobs.append(store.read(name)[0] or b"")
        headers, rows = readable_rows(load_ndjson(blobs))
        Path(a.out).write_bytes(to_csv_bytes(headers, rows))
        Path(a.out).with_name(Path(a.out).stem + "_字典.csv").write_bytes(dictionary_csv_bytes())
        print(json.dumps({"rows": len(rows), "out": a.out}, ensure_ascii=False))
        return 0
    if not a.bucket or not a.project:
        print(json.dumps({"severity": "ERROR", "message": "--bucket and --project required"}))
        return 2
    plan = plan_load(GcsObjectStore(a.bucket), a.bucket, a.project, a.dataset, date.fromisoformat(a.date), demo=a.demo)
    info = {"table": plan.table, "staging": plan.staging, "objects": len(plan.uris), "job_base": plan.job_base,
            "problems": plan.problems, "mode": "execute" if a.execute else "plan_only"}
    if a.execute:
        if not a.location:
            print(json.dumps({"severity": "ERROR", "message": "--location (BQ_LOCATION) required with --execute"}))
            return 2
        info["outcome"] = execute_load(plan, GoogleBigQueryClient(a.project, a.location))
    print(json.dumps(info, ensure_ascii=False))
    return 0 if info.get("outcome", "planned") in ("planned", "loaded", "already_loaded", "in_progress") else 1


if __name__ == "__main__":
    raise SystemExit(main())
