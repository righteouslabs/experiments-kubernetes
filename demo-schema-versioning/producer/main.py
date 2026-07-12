"""CloudEvents-over-Kafka producer.

Emits one CloudEvent per active schema version on a fixed interval. Each
event is written to topic `events` in binary content mode — CloudEvent
attributes become Kafka headers (`ce_<name>`), payload is JSON.

The KafkaSource ingests this topic into a Kafka-class Broker; Triggers
filter on the `schemaversion` attribute and fan out to consumer-<version>
Knative Services.

Env:
  KAFKA_BOOTSTRAP   default redpanda:9092
  KAFKA_TOPIC       default events
  ACTIVE_VERSIONS   csv of versions to emit, e.g. v1,v2,v3
  EMIT_INTERVAL_MS  default 1000
"""

from __future__ import annotations

import logging
import os
import signal
import sys
import time
import uuid
from pathlib import Path
from typing import Any, Iterable, cast

from confluent_kafka import Producer
from pydantic import BaseModel

THIS_DIR = Path(__file__).resolve().parent
REPO_DIR = THIS_DIR.parent
if str(REPO_DIR) not in sys.path:
    sys.path.insert(0, str(REPO_DIR))

from producer.emitters import EMITTERS  # noqa: E402
from schemas import versions as known_versions  # noqa: E402

log = logging.getLogger("producer")


def parse_versions(raw: str) -> list[str]:
    out: list[str] = []
    for v in (raw or "").split(","):
        v = v.strip()
        if not v:
            continue
        if v not in known_versions():
            log.warning("ignoring unknown version %r (known=%s)", v, known_versions())
            continue
        out.append(v)
    return out


def ce_headers(version: str, event_id: str, source: str) -> list[tuple[str, bytes]]:
    return [
        ("ce_specversion", b"1.0"),
        ("ce_id", event_id.encode()),
        ("ce_source", source.encode()),
        ("ce_type", f"com.righteous.event.{version}".encode()),
        ("ce_schemaversion", version.encode()),
        ("content-type", b"application/json"),
    ]


def emit_once(producer: Producer, topic: str, source: str, active: Iterable[str]) -> None:
    for version in active:
        sample: BaseModel = EMITTERS[version]()
        event_id = str(uuid.uuid4())
        payload = sample.model_dump_json().encode()
        headers = cast(Any, ce_headers(version, event_id, source))
        producer.produce(
            topic=topic,
            key=event_id.encode(),
            value=payload,
            headers=headers,
        )
    producer.poll(0)


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    bootstrap = os.environ.get("KAFKA_BOOTSTRAP", "redpanda:9092")
    topic = os.environ.get("KAFKA_TOPIC", "events")
    active = parse_versions(os.environ.get("ACTIVE_VERSIONS", "v1"))
    interval_ms = int(os.environ.get("EMIT_INTERVAL_MS", "1000"))
    source = os.environ.get("CE_SOURCE", "demo-schema-versioning/producer")

    if not active:
        log.error("no active versions configured (set ACTIVE_VERSIONS)")
        return 2

    log.info("producer starting bootstrap=%s topic=%s active=%s interval_ms=%s", bootstrap, topic, active, interval_ms)
    producer = Producer({"bootstrap.servers": bootstrap, "client.id": "schema-versioning-producer"})

    stop = False

    def _stop(*_a: object) -> None:
        nonlocal stop
        stop = True

    signal.signal(signal.SIGTERM, _stop)
    signal.signal(signal.SIGINT, _stop)

    while not stop:
        try:
            emit_once(producer, topic, source, active)
        except Exception:  # noqa: BLE001
            log.exception("emit failed")
        time.sleep(interval_ms / 1000.0)

    producer.flush(5)
    log.info("producer stopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
