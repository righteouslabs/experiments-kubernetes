"""CloudEvents-over-HTTP consumer.

Knative Eventing delivers CloudEvents in binary content mode: HTTP headers
`Ce-Specversion`, `Ce-Id`, `Ce-Source`, `Ce-Type`, `Ce-Schemaversion`, body
is JSON. We validate the body against the Pydantic model for our
configured SCHEMA_VERSION and call the matching handler.

Mismatched versions are dead-lettered with HTTP 422 (Knative DLQ if
configured, drop otherwise). Validation failures: HTTP 400.

Env:
  SCHEMA_VERSION   required, e.g. v1
  PORT             default 8080 (Knative-set)
"""

from __future__ import annotations

import logging
import os
import sys
import time
from collections import deque
from pathlib import Path
from threading import Lock

from fastapi import FastAPI, HTTPException, Request, Response
from pydantic import ValidationError

THIS_DIR = Path(__file__).resolve().parent
REPO_DIR = THIS_DIR.parent
if str(REPO_DIR) not in sys.path:
    sys.path.insert(0, str(REPO_DIR))

from consumer.handlers import load_handler  # noqa: E402
from schemas import model_for, versions  # noqa: E402

log = logging.getLogger("consumer")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

SCHEMA_VERSION = os.environ.get("SCHEMA_VERSION", "v1")
if SCHEMA_VERSION not in versions():
    log.error("invalid SCHEMA_VERSION=%s known=%s", SCHEMA_VERSION, versions())

MODEL = model_for(SCHEMA_VERSION)
HANDLE = load_handler(SCHEMA_VERSION)

app = FastAPI(title=f"consumer-{SCHEMA_VERSION}")

_lock = Lock()
_counters = {"received": 0, "accepted": 0, "rejected_version": 0, "rejected_invalid": 0}
_recent: deque[dict[str, object]] = deque(maxlen=50)


def _bump(name: str) -> None:
    with _lock:
        _counters[name] = _counters.get(name, 0) + 1


@app.get("/healthz")
async def healthz() -> dict[str, str]:
    return {"status": "ok", "schema_version": SCHEMA_VERSION}


@app.get("/stats")
async def stats() -> dict[str, object]:
    with _lock:
        return {
            "schema_version": SCHEMA_VERSION,
            "counters": dict(_counters),
            "recent": list(_recent),
            "ts_ms": int(time.time() * 1000),
        }


@app.post("/")
async def receive(request: Request) -> Response:
    _bump("received")
    headers = {k.lower(): v for k, v in request.headers.items()}
    incoming_version = headers.get("ce-schemaversion", "")
    event_id = headers.get("ce-id", "")

    if incoming_version != SCHEMA_VERSION:
        _bump("rejected_version")
        log.warning("version mismatch event_id=%s incoming=%s expected=%s", event_id, incoming_version, SCHEMA_VERSION)
        raise HTTPException(status_code=422, detail=f"expected {SCHEMA_VERSION}, got {incoming_version!r}")

    try:
        body = await request.json()
        event = MODEL.model_validate(body)
    except (ValidationError, ValueError) as e:
        _bump("rejected_invalid")
        log.warning("validation failed event_id=%s err=%s", event_id, e)
        raise HTTPException(status_code=400, detail=str(e)) from e

    try:
        result = HANDLE(event)
    except Exception as e:  # noqa: BLE001
        _bump("rejected_invalid")
        log.exception("handler raised event_id=%s", event_id)
        raise HTTPException(status_code=500, detail=str(e)) from e

    _bump("accepted")
    with _lock:
        _recent.append(
            {
                "id": event_id,
                "version": SCHEMA_VERSION,
                "ts_ms": int(time.time() * 1000),
                "result": result,
            }
        )
    return Response(status_code=204)
