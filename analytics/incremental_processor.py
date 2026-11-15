"""
Hospital Equipment incremental outbox processor.

Flow:
  PostgreSQL outbox JSONL in S3
      -> S3 notification in SQS
      -> rebuild the 10 approved analytics datasets from PostgreSQL
      -> write one CSV batch per dataset to the matching incremental S3 folder
      -> acknowledge SQS only after every CSV upload succeeds.

Important:
- This processor writes CSV batches to S3. It does NOT execute Snowflake MERGE statements.
- It reuses planner_analytics.export transformations.
- Bookings and work_order_history have no direct report target.
- No full-snapshot Snowflake task is started or changed.
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
import logging
import os
import signal
import sys
import time
from datetime import datetime, timezone
from typing import Any
from urllib.parse import unquote_plus

import boto3
import pandas as pd
import psycopg
from botocore.exceptions import BotoCoreError, ClientError

try:
    from planner_analytics.export import (
        load_from_pg, clean, build_reports, assert_safe
    )
except ImportError as exc:
    raise SystemExit(
        "Cannot import planner_analytics.export. Run this file from the "
        "analytics project root, with planner_analytics/ on PYTHONPATH."
    ) from exc



AWS_REGION = os.getenv("AWS_REGION", "ap-south-1")
S3_BUCKET = os.getenv(
    "S3_BUCKET", "hospital-equipment-analytics-srinivas"
)
OUTBOX_PREFIX = os.getenv(
    "OUTBOX_S3_PREFIX", "hospital-equipment/outbox/events/"
).strip("/")
INCREMENTAL_PREFIX = os.getenv(
    "INCREMENTAL_S3_PREFIX", "hospital-equipment/incremental/"
).strip("/")
SQS_QUEUE_URL = os.getenv("OUTBOX_NOTIFICATION_QUEUE_URL")
DATABASE_URL = os.getenv("DATABASE_URL")
EXPORT_SALT = os.getenv("EXPORT_SALT", "")

POLL_INTERVAL = int(os.getenv("PROCESSOR_POLL_INTERVAL", "10"))
DAYS = int(os.getenv("INCREMENTAL_REBUILD_DAYS", "35"))

WAIT_TIME_SECONDS = 20
VISIBILITY_TIMEOUT = 300
MAX_MESSAGES = 5
TEST_FILE_NAME = "notification-test.jsonl"

if not DATABASE_URL:
    raise SystemExit("DATABASE_URL is required.")

if not SQS_QUEUE_URL:
    raise SystemExit("OUTBOX_NOTIFICATION_QUEUE_URL is required.")

if not EXPORT_SALT:
    raise SystemExit(
        "EXPORT_SALT is required. Staff identifiers must be pseudonymised "
        "before incremental CSV files are uploaded."
    )

if DAYS < 1:
    raise SystemExit("INCREMENTAL_REBUILD_DAYS must be at least 1.")


logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)

log = logging.getLogger("hospital-incremental-processor")

s3 = boto3.client("s3", region_name=AWS_REGION)
sqs = boto3.client("sqs", region_name=AWS_REGION)

STOP = False



TARGETS: dict[str, dict[str, Any]] = {
    "equipment": {
        "table": "EQUIPMENT_LATEST",
        "key": ["ASSET_TAG"],
        "columns": [
            "ASSET_TAG", "NAME", "CATEGORY", "DEPARTMENT",
            "LIFECYCLE_STATUS", "MANUFACTURER", "MODEL",
            "PURCHASE_DATE", "PURCHASE_COST", "WARRANTY_END",
            "CONTRACT_TYPE", "CONTRACT_END", "LOCATION",
            "MAINTENANCE_INTERVAL_DAYS", "LAST_MAINTENANCE_AT",
            "NEXT_MAINTENANCE_DUE", "CALIBRATION_REQUIRED",
            "LAST_CALIBRATION_AT", "NEXT_CALIBRATION_DUE",
            "CALIBRATION_FAILED", "IN_SERVICE_FROM", "RETIRED_AT",
            "MAINTENANCE_THRESHOLD_HOURS",
            "USAGE_HOURS_SINCE_MAINTENANCE",
        ],
        "folder": "equipment",
    },
    "usage_clean": {
        "table": "USAGE_CLEAN_LATEST",
        "key": ["USAGE_ID"],
        "columns": [
            "USAGE_ID", "ASSET_TAG", "DEPARTMENT", "START_AT",
            "END_AT", "SOURCE", "BATCH_ID", "USER_KEY", "DURATION_H",
        ],
        "folder": "usage_clean",
    },
    "maintenance": {
        "table": "MAINTENANCE_LATEST",
        "key": ["MAINTENANCE_ID"],
        "columns": [
            "MAINTENANCE_ID", "ASSET_TAG", "KIND", "PRIORITY",
            "STATUS", "SCHEDULED_START", "SCHEDULED_END",
            "ACTUAL_START", "ACTUAL_END", "NEXT_DUE_DATE",
            "COMPLETED_ON_TIME", "DURATION_H", "ENGINEER_KEY",
        ],
        "folder": "maintenance",
    },
    "calibration": {
        "table": "CALIBRATION_LATEST",
        "key": ["CALIBRATION_ID"],
        "columns": [
            "CALIBRATION_ID", "ASSET_TAG", "STATUS", "RESULT",
            "SCHEDULED_FOR", "PERFORMED_AT", "NEXT_DUE_DATE",
            "CERTIFICATE_NUMBER", "ENGINEER_KEY",
        ],
        "folder": "calibration",
    },
    "breakdowns": {
        "table": "BREAKDOWNS_LATEST",
        "key": ["WORK_ORDER_ID"],
        "columns": [
            "WORK_ORDER_ID", "ASSET_TAG", "PRIORITY", "SEVERITY",
            "UNSAFE", "STATUS", "REPORTED_AT", "TRIAGED_AT",
            "STARTED_AT", "RESOLVED_AT", "VERIFIED_AT", "CLOSED_AT",
            "DOWNTIME_HOURS", "RESOLUTION_H", "REPORTER_KEY",
            "ENGINEER_KEY",
        ],
        "folder": "breakdowns",
    },
    "downtime": {
        "table": "DOWNTIME_LATEST",
        "key": ["DOWNTIME_ID"],
        "columns": [
            "DOWNTIME_ID", "ASSET_TAG", "START_AT", "END_AT",
            "REASON", "DURATION_H",
        ],
        "folder": "downtime",
    },
    "department_history": {
        "table": "DEPARTMENT_HISTORY_LATEST",
        "key": ["ASSET_TAG", "DEPARTMENT", "EFFECTIVE_FROM"],
        "columns": [
            "ASSET_TAG", "DEPARTMENT", "EFFECTIVE_FROM", "EFFECTIVE_TO",
        ],
        "folder": "department_history",
    },
    "asset_day_utilization": {
        "table": "ASSET_DAY_UTILIZATION_LATEST",
        "key": ["DAY", "ASSET_TAG", "DEPARTMENT"],
        "columns": [
            "DAY", "ASSET_TAG", "DEPARTMENT", "TOTAL_H",
            "UNAVAILABLE_H", "AVAILABLE_H", "USED_H", "IDLE_H",
            "UTILIZATION", "FLAG",
        ],
        "folder": "asset_day_utilization",
    },
    "department_day_utilization": {
        "table": "DEPARTMENT_DAY_UTILIZATION_LATEST",
        "key": ["DAY", "DEPARTMENT"],
        "columns": [
            "DAY", "DEPARTMENT", "TOTAL_H", "UNAVAILABLE_H",
            "AVAILABLE_H", "USED_H", "IDLE_H", "UTILIZATION", "FLAG",
        ],
        "folder": "department_day_utilization",
    },
    "maintenance_compliance": {
        "table": "MAINTENANCE_COMPLIANCE_LATEST",
        "key": ["METRIC"],
        "columns": ["METRIC", "VALUE"],
        "folder": "maintenance_compliance",
    },
}


META_COLUMNS = [
    "SOURCE_EVENT_ID",
    "OPERATION_ID",
    "TARGET_TABLE",
    "OPERATION",
    "BUSINESS_KEY",
    "SOURCE_FILE_NAME",
    "LOADED_AT",
]


SUPPORTED_SOURCE_TYPES = {
    "departments",
    "assets",
    "bookings",
    "maintenance_windows",
    "transfers",
    "usage_events",
    "downtime_events",
    "asset_department_history",
    "calibrations",
    "work_orders",
    "work_order_history",
}

NO_REPORT_TARGET_TYPES = {
    "bookings",
    "work_order_history",
}


DELETE_KEY_MAP = {
    "assets": ("equipment", "asset_tag", "ASSET_TAG"),
    "usage_events": ("usage_clean", "id", "USAGE_ID"),
    "maintenance_windows": ("maintenance", "id", "MAINTENANCE_ID"),
    "calibrations": ("calibration", "id", "CALIBRATION_ID"),
    "work_orders": ("breakdowns", "id", "WORK_ORDER_ID"),
    "downtime_events": ("downtime", "id", "DOWNTIME_ID"),
}



def parse_s3_notification(body: str) -> list[dict[str, str]]:
    payload = json.loads(body)

    if "Message" in payload:
        payload = json.loads(payload["Message"])

    objects = []

    for record in payload.get("Records", []):
        if record.get("eventSource") != "aws:s3":
            continue

        bucket = record["s3"]["bucket"]["name"]
        key = unquote_plus(record["s3"]["object"]["key"])

        objects.append({
            "bucket": bucket,
            "key": key,
        })

    return objects


def read_jsonl(bucket: str, key: str) -> list[dict[str, Any]]:
    response = s3.get_object(Bucket=bucket, Key=key)
    text = response["Body"].read().decode("utf-8")

    events = []

    for line_no, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue

        try:
            event = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(
                f"Invalid JSONL at {key}:{line_no}: {exc}"
            ) from exc

        required = {
            "event_id", "aggregate_type", "aggregate_id",
            "event_type", "payload",
        }

        missing = required - set(event)

        if missing:
            raise ValueError(
                f"Event at {key}:{line_no} missing fields: {sorted(missing)}"
            )

        if isinstance(event["payload"], str):
            event["payload"] = json.loads(event["payload"])

        if not isinstance(event["payload"], dict):
            raise ValueError(
                f"Event {event['event_id']} payload must be a JSON object"
            )

        events.append(event)

    if not events:
        raise ValueError(
            f"No outbox events found in s3://{bucket}/{key}"
        )

    return events


def scalar(value: Any) -> Any:
    if value is None:
        return ""

    try:
        if pd.isna(value):
            return ""
    except (TypeError, ValueError):
        pass

    if isinstance(value, (pd.Timestamp, datetime)):
        return value.isoformat()

    if hasattr(value, "item"):
        try:
            return value.item()
        except Exception:
            pass

    return value


def business_key(
    row: dict[str, Any],
    key_columns: list[str]
) -> str:
    parts = []

    for col in key_columns:
        value = scalar(row.get(col))

        if value == "":
            parts.append("<NULL>")
        else:
            parts.append(str(value))

    return "|".join(parts)


def source_event_marker(
    events: list[dict[str, Any]]
) -> str:
    ids = sorted({
        str(event["event_id"])
        for event in events
    })

    return ",".join(ids)


def operation_id(
    source_key: str,
    target: str,
    op: str,
    key: str,
    content_hash: str
) -> str:
    raw = "\x1f".join([
        source_key,
        target,
        op,
        key,
        content_hash,
    ])

    return hashlib.sha256(
        raw.encode("utf-8")
    ).hexdigest()


def frame_to_records(
    df: pd.DataFrame,
    target_name: str
) -> list[dict[str, Any]]:
    target = TARGETS[target_name]
    expected = target["columns"]

    frame = df.copy()
    frame.columns = [
        str(column).upper()
        for column in frame.columns
    ]

    missing = [
        column for column in expected
        if column not in frame.columns
    ]

    extra = [
        column for column in frame.columns
        if column not in expected
    ]

    if missing or extra:
        raise ValueError(
            f"{target_name} schema mismatch. "
            f"Missing={missing}; unexpected={extra}"
        )

    return [
        {
            column: scalar(row[column])
            for column in expected
        }
        for row in frame[expected].to_dict(orient="records")
    ]


def make_delete_rows(
    events: list[dict[str, Any]]
) -> dict[str, list[dict[str, Any]]]:
    deletes = {
        name: []
        for name in TARGETS
    }

    for event in events:
        source = str(
            event["aggregate_type"]
        ).lower()

        event_type = str(
            event["event_type"]
        ).upper().split(".")[-1]

        if event_type != "DELETE":
            continue

        if source not in DELETE_KEY_MAP:
            continue

        target_name, old_field, target_key = (
            DELETE_KEY_MAP[source]
        )

        old = event["payload"].get("old") or {}
        key_value = old.get(old_field)

        if key_value is None:
            raise ValueError(
                f"DELETE event {event['event_id']} for {source} "
                f"has no old.{old_field}"
            )

        deletes[target_name].append({
            target_key: str(key_value)
        })

    return deletes


def make_csv(
    target_name: str,
    rows: list[dict[str, Any]],
    deletes: list[dict[str, Any]],
    events: list[dict[str, Any]],
    source_key: str,
    loaded_at: datetime,
) -> tuple[str, int]:

    target = TARGETS[target_name]

    table = target["table"]
    columns = target["columns"]

    event_marker = source_event_marker(events)

    content_hash = hashlib.sha256(
        json.dumps(
            {
                "rows": rows,
                "deletes": deletes,
            },
            sort_keys=True,
            default=str,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()

    output = io.StringIO(newline="")

    writer = csv.DictWriter(
        output,
        fieldnames=columns + META_COLUMNS,
        extrasaction="raise",
        lineterminator="\n",
    )

    writer.writeheader()

    operations = (
        [("UPSERT", row) for row in rows]
        + [("DELETE", row) for row in deletes]
    )

    count = 0

    for op, source_row in operations:
        row = {
            column: source_row.get(column, "")
            for column in columns
        }

        key = business_key(
            row,
            target["key"]
        )

        row.update({
            "SOURCE_EVENT_ID": event_marker,
            "OPERATION_ID": operation_id(
                source_key,
                table,
                op,
                key,
                content_hash,
            ),
            "TARGET_TABLE": table,
            "OPERATION": op,
            "BUSINESS_KEY": key,
            "SOURCE_FILE_NAME": source_key,
            "LOADED_AT": loaded_at.isoformat(),
        })

        writer.writerow({
            key: scalar(value)
            for key, value in row.items()
        })

        count += 1

    return output.getvalue(), count



def rebuild_reports() -> dict[str, pd.DataFrame]:
    now = pd.Timestamp(
        datetime.now(timezone.utc)
    )

    since = now - pd.Timedelta(days=DAYS)

    frames = load_from_pg(
        DATABASE_URL,
        since
    )

    cleaned, quality = clean(
        frames,
        EXPORT_SALT
    )

    reports = build_reports(
        cleaned,
        now,
        DAYS
    )

    if set(reports) != set(TARGETS):
        raise ValueError(
            f"Exporter returned datasets {sorted(reports)}; "
            f"expected {sorted(TARGETS)}"
        )

    for name, frame in reports.items():
        assert_safe(name, frame)

        log.info(
            "Rebuilt %-28s rows=%d",
            name,
            len(frame)
        )

    log.info(
        "Usage quality report: %s",
        quality.get("usage", {})
    )

    return reports



def upload_report_batches(
    reports: dict[str, pd.DataFrame],
    events: list[dict[str, Any]],
    source_key: str,
    loaded_at: datetime,
) -> list[dict[str, Any]]:

    deletes = make_delete_rows(events)

    event_digest = hashlib.sha256(
        ",".join(
            sorted(str(event["event_id"]) for event in events)
        ).encode("utf-8")
    ).hexdigest()[:20]

    uploaded: list[dict[str, Any]] = []

    for name, target in TARGETS.items():
        rows = frame_to_records(reports[name], name)

        csv_text, count = make_csv(
            name,
            rows,
            deletes[name],
            events,
            source_key,
            loaded_at,
        )

        csv_bytes = csv_text.encode("utf-8")
        csv_sha256 = hashlib.sha256(csv_bytes).hexdigest()
        payload_hash = csv_sha256[:24]

        key = (
            f"{INCREMENTAL_PREFIX.rstrip('/')}/"
            f"{target['folder']}/batches/"
            f"batch-{event_digest}-{payload_hash}.csv"
        )

        s3.put_object(
            Bucket=S3_BUCKET,
            Key=key,
            Body=csv_bytes,
            ContentType="text/csv; charset=utf-8",
            ServerSideEncryption="AES256",
            Metadata={
                "source-event-count": str(len(events)),
                "target-table": target["table"],
                "row-count": str(count),
                "sha256": csv_sha256,
            },
        )

        uploaded.append({
            "dataset": name,
            "target_table": target["table"],
            "s3_key": key,
            "row_count": count,
            "sha256": csv_sha256,
        })

        log.info(
            "Uploaded %s rows=%d to s3://%s/%s",
            target["table"],
            count,
            S3_BUCKET,
            key,
        )

    return uploaded


def upload_completion_manifest(
    events: list[dict[str, Any]],
    source_key: str,
    uploaded: list[dict[str, Any]],
) -> str:

    expected_datasets = set(TARGETS)
    uploaded_datasets = {
        item["dataset"] for item in uploaded
    }

    if uploaded_datasets != expected_datasets:
        missing = sorted(expected_datasets - uploaded_datasets)
        unexpected = sorted(uploaded_datasets - expected_datasets)

        raise RuntimeError(
            f"Cannot create completion manifest. "
            f"Missing={missing}; unexpected={unexpected}"
        )

    if len(uploaded) != len(TARGETS):
        raise RuntimeError(
            f"Expected {len(TARGETS)} CSV files, got {len(uploaded)}"
        )

    event_ids = sorted({
        str(event["event_id"]) for event in events
    })

    event_digest = hashlib.sha256(
        ",".join(event_ids).encode("utf-8")
    ).hexdigest()[:20]

    manifest = {
        "schema_version": 1,
        "batch_id": event_digest,
        "status": "COMPLETE",
        "source_file": source_key,
        "source_event_ids": event_ids,
        "expected_dataset_count": len(TARGETS),
        "created_at": datetime.now(timezone.utc).isoformat(),
        "datasets": sorted(
            uploaded,
            key=lambda item: item["dataset"],
        ),
    }

    manifest_bytes = json.dumps(
        manifest,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")

    manifest_hash = hashlib.sha256(
        manifest_bytes
    ).hexdigest()

    manifest_key = (
        f"{INCREMENTAL_PREFIX.rstrip('/')}/manifests/"
        f"batch-{event_digest}-{manifest_hash[:24]}.json"
    )

    s3.put_object(
        Bucket=S3_BUCKET,
        Key=manifest_key,
        Body=manifest_bytes,
        ContentType="application/json",
        ServerSideEncryption="AES256",
        Metadata={
            "batch-id": event_digest,
            "dataset-count": str(len(TARGETS)),
            "manifest-sha256": manifest_hash,
        },
    )

    log.info(
        "Uploaded COMPLETE manifest for batch %s: s3://%s/%s",
        event_digest,
        S3_BUCKET,
        manifest_key,
    )

    return manifest_key



def validate_event_types(
    events: list[dict[str, Any]]
) -> None:

    unknown = sorted({
        str(event["aggregate_type"]).lower()
        for event in events
        if str(event["aggregate_type"]).lower()
        not in SUPPORTED_SOURCE_TYPES
    })

    if unknown:
        raise ValueError(
            f"Unsupported outbox aggregate types: {unknown}"
        )


def process_s3_object(
    bucket: str,
    key: str
) -> None:

    if bucket != S3_BUCKET:
        raise ValueError(
            f"Unexpected bucket {bucket}; expected {S3_BUCKET}"
        )

    if (
        not key.startswith(OUTBOX_PREFIX + "/")
        and key != OUTBOX_PREFIX
    ):
        raise ValueError(
            f"Object is outside configured outbox prefix: {key}"
        )

    if not key.endswith(".jsonl"):
        log.info(
            "Ignoring non-JSONL object %s",
            key
        )
        return

    if key.endswith(TEST_FILE_NAME):
        log.info(
            "Ignoring S3 notification test object %s",
            key
        )
        return

    events = read_jsonl(
        bucket,
        key
    )

    validate_event_types(events)

    relevant = [
        event
        for event in events
        if str(event["aggregate_type"]).lower()
        not in NO_REPORT_TARGET_TYPES
    ]

    no_target = len(events) - len(relevant)

    if not relevant:
        log.info(
            "All %d events in %s are known no-report-target events; "
            "no CSV emitted.",
            len(events),
            key,
        )
        return

    log.info(
        "Processing raw object %s: events=%d "
        "report-affecting=%d no-report-target=%d",
        key,
        len(events),
        len(relevant),
        no_target,
    )

    # Rebuild all 10 reports from current PostgreSQL state.
    reports = rebuild_reports()

    # Use S3 LastModified so retries of the same source object
    # retain the same timestamp.
    source_metadata = s3.head_object(
        Bucket=bucket,
        Key=key
    )

    loaded_at = source_metadata[
        "LastModified"
    ].astimezone(timezone.utc)

    
    uploaded = upload_report_batches(
        reports,
        relevant,
        key,
        loaded_at,
    )

    if len(uploaded) != len(TARGETS):
        raise RuntimeError(
            f"Only {len(uploaded)}/{len(TARGETS)} "
            f"report batches were uploaded"
        )

    manifest_key = upload_completion_manifest(
        relevant,
        key,
        uploaded,
    )

    log.info(
        "Batch processing complete. "
        "CSV files=%d manifest=%s",
        len(uploaded),
        manifest_key,
    )



def process_message(
    message: dict[str, Any]
) -> bool:

    receipt = message["ReceiptHandle"]

    try:
        sqs.change_message_visibility(
            QueueUrl=SQS_QUEUE_URL,
            ReceiptHandle=receipt,
            VisibilityTimeout=VISIBILITY_TIMEOUT,
        )

        objects = parse_s3_notification(
            message["Body"]
        )

        for item in objects:
            process_s3_object(
                item["bucket"],
                item["key"]
            )

        # Acknowledge only after all raw objects and
        # all 10 output batches have succeeded.
        sqs.delete_message(
            QueueUrl=SQS_QUEUE_URL,
            ReceiptHandle=receipt
        )

        log.info(
            "Acknowledged SQS notification after successful processing."
        )

        return True

    except Exception:
        log.exception(
            "Processing failed; SQS message will be retried."
        )

        try:
            sqs.change_message_visibility(
                QueueUrl=SQS_QUEUE_URL,
                ReceiptHandle=receipt,
                VisibilityTimeout=0,
            )

        except Exception:
            log.exception(
                "Could not reset SQS visibility; "
                "normal timeout will apply."
            )

        return False


def stop_handler(
    _signum,
    _frame
):
    global STOP

    STOP = True

    log.info(
        "Shutdown requested; finishing current message before exit."
    )


def main() -> int:

    signal.signal(
        signal.SIGINT,
        stop_handler
    )

    signal.signal(
        signal.SIGTERM,
        stop_handler
    )

    log.info(
        "Hospital Equipment incremental processor starting."
    )

    log.info(
        "Bucket=%s outbox=%s incremental=%s days=%s",
        S3_BUCKET,
        OUTBOX_PREFIX,
        INCREMENTAL_PREFIX,
        DAYS,
    )

    log.info(
        "Targets=%s",
        ", ".join(
            target["table"]
            for target in TARGETS.values()
        ),
    )

    while not STOP:
        try:
            response = sqs.receive_message(
                QueueUrl=SQS_QUEUE_URL,
                MaxNumberOfMessages=MAX_MESSAGES,
                WaitTimeSeconds=WAIT_TIME_SECONDS,
                VisibilityTimeout=VISIBILITY_TIMEOUT,
                AttributeNames=[
                    "ApproximateReceiveCount"
                ],
            )

            messages = response.get(
                "Messages",
                []
            )

            if not messages:
                continue

            for message in messages:
                if STOP:
                    break

                process_message(message)

        except (
            ClientError,
            BotoCoreError,
            psycopg.Error,
        ):
            log.exception(
                "Infrastructure/database error in polling loop."
            )

            time.sleep(POLL_INTERVAL)

        except Exception:
            log.exception(
                "Unexpected polling-loop failure."
            )

            time.sleep(POLL_INTERVAL)

    log.info(
        "Incremental processor stopped."
    )

    return 0


if __name__ == "__main__":
    sys.exit(main())