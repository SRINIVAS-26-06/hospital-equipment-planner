
import json
import logging
import os
import subprocess
import sys
import threading
import time

import boto3
from botocore.exceptions import BotoCoreError, ClientError


# --------------------------------------------------
# CONFIGURATION
# --------------------------------------------------

AWS_REGION = os.getenv("AWS_REGION", "ap-south-1")
QUEUE_URL = os.getenv("SQS_QUEUE_URL", "")

POLL_WAIT_SECONDS = 20
VISIBILITY_TIMEOUT = 900
HEARTBEAT_INTERVAL = 300

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s"
)

logger = logging.getLogger(__name__)


# --------------------------------------------------
# AWS SQS CLIENT
# --------------------------------------------------

sqs = boto3.client(
    "sqs",
    region_name=AWS_REGION
)


# --------------------------------------------------
# VISIBILITY TIMEOUT HEARTBEAT
# --------------------------------------------------

def maintain_visibility(receipt_handle, stop_event):

    while not stop_event.wait(HEARTBEAT_INTERVAL):

        try:
            sqs.change_message_visibility(
                QueueUrl=QUEUE_URL,
                ReceiptHandle=receipt_handle,
                VisibilityTimeout=VISIBILITY_TIMEOUT
            )

            logger.info("SQS message visibility extended")

        except (BotoCoreError, ClientError):
            logger.exception("Could not extend message visibility")
            return


# --------------------------------------------------
# RUN ANALYTICS EXPORTER
# --------------------------------------------------

def run_export():

    logger.info("Starting hospital equipment analytics export")

    command = [
        sys.executable,
        "-m",
        "planner_analytics.export",
        "--days",
        "90",
        "--since-days",
        "90",
        "--out",
        "/app/out"
    ]

    result = subprocess.run(command, check=False)

    if result.returncode != 0:
        raise RuntimeError(
            f"Analytics export failed with exit code {result.returncode}"
        )

    logger.info("Analytics export completed successfully")


# --------------------------------------------------
# PROCESS SQS MESSAGE
# --------------------------------------------------

def process_message(message):

    receipt_handle = message["ReceiptHandle"]

    stop_event = threading.Event()

    heartbeat_thread = threading.Thread(
        target=maintain_visibility,
        args=(receipt_handle, stop_event),
        daemon=True
    )

    try:

        body = json.loads(message["Body"])

        logger.info(
            "Received message. Receive count: %s",
            message.get("Attributes", {}).get("ApproximateReceiveCount")
        )

        if body.get("job") != "hospital-equipment-analytics":
            raise ValueError("Unknown job message")

        if body.get("action") != "run-export":
            raise ValueError("Unknown job action")

        # Start extending visibility while export runs.
        heartbeat_thread.start()

        run_export()

        # Delete only after successful export.
        sqs.delete_message(
            QueueUrl=QUEUE_URL,
            ReceiptHandle=receipt_handle
        )

        logger.info("SQS message deleted after successful export")

    except Exception:

        logger.exception(
            "Message processing failed. Message will be retried."
        )

    finally:

        stop_event.set()

        if heartbeat_thread.is_alive():
            heartbeat_thread.join(timeout=5)


# --------------------------------------------------
# MAIN WORKER LOOP
# --------------------------------------------------

def main():

    if not QUEUE_URL:
        raise ValueError("SQS_QUEUE_URL environment variable is missing")

    logger.info("Hospital analytics SQS worker started")
    logger.info("Polling SQS queue")

    while True:

        try:

            response = sqs.receive_message(
                QueueUrl=QUEUE_URL,
                MaxNumberOfMessages=1,
                WaitTimeSeconds=POLL_WAIT_SECONDS,
                AttributeNames=["ApproximateReceiveCount"]
            )

            messages = response.get("Messages", [])

            for message in messages:
                process_message(message)

        except (BotoCoreError, ClientError):
            logger.exception("AWS SQS communication error")
            time.sleep(10)

        except Exception:
            logger.exception("Unexpected worker error")
            time.sleep(10)


if __name__ == "__main__":
    main()
