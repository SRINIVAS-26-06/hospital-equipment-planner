
import hashlib
import json
import logging
import os
import time
from io import BytesIO

import boto3
import psycopg
from psycopg.rows import dict_row
from botocore.exceptions import BotoCoreError, ClientError


# --------------------------------------------------
# CONFIGURATION
# --------------------------------------------------

DATABASE_URL = os.getenv("DATABASE_URL")

AWS_REGION = os.getenv("AWS_REGION", "ap-south-1")
S3_BUCKET = os.getenv("S3_BUCKET")
S3_PREFIX = os.getenv(
    "OUTBOX_S3_PREFIX",
    "hospital-equipment/outbox/events/"
)

BATCH_SIZE = int(os.getenv("OUTBOX_BATCH_SIZE", "20"))
POLL_INTERVAL = int(os.getenv("OUTBOX_POLL_INTERVAL", "10"))
MAX_ATTEMPTS = 8
LOCK_TIMEOUT_MINUTES = 15

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s"
)

logger = logging.getLogger(__name__)

s3 = boto3.client("s3", region_name=AWS_REGION)


# --------------------------------------------------
# CLAIM PENDING EVENTS
# --------------------------------------------------

def claim_events():

    query = """
        WITH candidates AS (
            SELECT event_id
            FROM outbox_events
            WHERE (
                (status = 'PENDING' AND available_at <= NOW())
                OR
                (
                    status = 'PROCESSING'
                    AND locked_at < NOW() - (%s * INTERVAL '1 minute')
                )
            )
            ORDER BY event_id
            FOR UPDATE SKIP LOCKED
            LIMIT %s
        )
        UPDATE outbox_events AS e
        SET
            status = 'PROCESSING',
            locked_at = NOW(),
            attempts = e.attempts + 1
        FROM candidates AS c
        WHERE e.event_id = c.event_id
        RETURNING
            e.event_id,
            e.aggregate_type,
            e.aggregate_id,
            e.event_type,
            e.payload,
            e.created_at,
            e.attempts;
    """

    with psycopg.connect(
        DATABASE_URL,
        row_factory=dict_row
    ) as conn:

        with conn.cursor() as cur:
            cur.execute(
                query,
                (LOCK_TIMEOUT_MINUTES, BATCH_SIZE)
            )
            events = cur.fetchall()

    return events


# --------------------------------------------------
# BUILD JSONL BATCH
# --------------------------------------------------

def build_jsonl(events):

    lines = []

    for event in events:

        record = {
            "event_id": event["event_id"],
            "aggregate_type": event["aggregate_type"],
            "aggregate_id": event["aggregate_id"],
            "event_type": event["event_type"],
            "payload": event["payload"],
            "created_at": event["created_at"].isoformat()
        }

        lines.append(json.dumps(record, default=str))

    return ("\n".join(lines) + "\n").encode("utf-8")


# --------------------------------------------------
# UPLOAD EVENTS TO S3
# --------------------------------------------------

def upload_to_s3(events):

    event_ids = sorted(
        event["event_id"] for event in events
    )

    content = build_jsonl(events)

    # Deterministic key makes retries overwrite the same object.
    digest = hashlib.sha256(
        ",".join(map(str, event_ids)).encode()
    ).hexdigest()[:12]

    key = (
        f"{S3_PREFIX.rstrip('/')}/"
        f"batch-{event_ids[0]}-{event_ids[-1]}-{digest}.jsonl"
    )

    s3.put_object(
        Bucket=S3_BUCKET,
        Key=key,
        Body=content,
        ContentType="application/x-ndjson",
        ServerSideEncryption="AES256"
    )

    logger.info(
        "Uploaded %s events to s3://%s/%s",
        len(events),
        S3_BUCKET,
        key
    )

    return key


# --------------------------------------------------
# MARK EVENTS AS PROCESSED
# --------------------------------------------------

def mark_processed(events):

    event_ids = [
        event["event_id"] for event in events
    ]

    with psycopg.connect(DATABASE_URL) as conn:

        with conn.cursor() as cur:

            cur.execute(
                """
                UPDATE outbox_events
                SET
                    status = 'PROCESSED',
                    processed_at = NOW(),
                    locked_at = NULL,
                    last_error = NULL
                WHERE event_id = ANY(%s)
                  AND status = 'PROCESSING';
                """,
                (event_ids,)
            )

            if cur.rowcount != len(event_ids):
                raise RuntimeError(
                    "Not all claimed events were marked processed"
                )

    logger.info(
        "Marked %s events as PROCESSED",
        len(event_ids)
    )


# --------------------------------------------------
# HANDLE FAILED EVENTS
# --------------------------------------------------

def release_failed_events(events, error):

    event_ids = [
        event["event_id"] for event in events
    ]

    with psycopg.connect(DATABASE_URL) as conn:

        with conn.cursor() as cur:

            cur.execute(
                """
                UPDATE outbox_events
                SET
                    status = CASE
                        WHEN attempts >= %s THEN 'FAILED'
                        ELSE 'PENDING'
                    END,
                    locked_at = NULL,
                    available_at = CASE
                        WHEN attempts >= %s THEN available_at
                        ELSE NOW() + (
                            LEAST(
                                POWER(2, attempts)::INTEGER,
                                900
                            ) * INTERVAL '1 second'
                        )
                    END,
                    last_error = LEFT(%s, 2000)
                WHERE event_id = ANY(%s)
                  AND status = 'PROCESSING';
                """,
                (
                    MAX_ATTEMPTS,
                    MAX_ATTEMPTS,
                    str(error),
                    event_ids
                )
            )

    logger.error(
        "Released %s events after failure",
        len(event_ids)
    )


# --------------------------------------------------
# PROCESS ONE BATCH
# --------------------------------------------------

def process_batch():

    events = claim_events()

    if not events:
        return False

    logger.info(
        "Claimed %s Outbox events",
        len(events)
    )

    try:

        upload_to_s3(events)

        mark_processed(events)

        return True

    except Exception as error:

        logger.exception("Outbox batch processing failed")

        try:
            release_failed_events(events, error)

        except Exception:
            logger.exception(
                "Could not release failed events; "
                "stale-lock recovery will handle them"
            )

        return False


# --------------------------------------------------
# MAIN WORKER LOOP
# --------------------------------------------------

def main():

    if not DATABASE_URL:
        raise ValueError("DATABASE_URL is missing")

    if not S3_BUCKET:
        raise ValueError("S3_BUCKET is missing")

    logger.info("Hospital Equipment Outbox Worker started")
    logger.info("S3 destination: %s", S3_BUCKET)
    logger.info("Batch size: %s", BATCH_SIZE)

    while True:

        try:

            processed = process_batch()

            if not processed:
                time.sleep(POLL_INTERVAL)

        except (BotoCoreError, ClientError):
            logger.exception("AWS communication error")
            time.sleep(POLL_INTERVAL)

        except Exception:
            logger.exception("Unexpected worker error")
            time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    main()
