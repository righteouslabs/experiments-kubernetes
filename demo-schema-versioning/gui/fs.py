"""File-system helpers — read/write producer emitters and consumer handlers."""

from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent  # demo-schema-versioning/
PRODUCER_EMITTERS = REPO / "producer" / "emitters"
CONSUMER_HANDLERS = REPO / "consumer" / "handlers"
CONSUMER_TEMPLATE = CONSUMER_HANDLERS / "_template.py"
SCHEMAS = REPO / "schemas"
K8S_SERVICES = REPO / "k8s" / "services"
K8S_TRIGGERS = REPO / "k8s" / "triggers"

_VERSION_RE = re.compile(r"^v\d+$")


def valid_version(v: str) -> bool:
    return bool(_VERSION_RE.match(v))


def list_versions_on_disk() -> list[str]:
    out: set[str] = set()
    for p in PRODUCER_EMITTERS.glob("v*.py"):
        if valid_version(p.stem):
            out.add(p.stem)
    for p in CONSUMER_HANDLERS.glob("v*.py"):
        if valid_version(p.stem):
            out.add(p.stem)
    return sorted(out)


def read_emitter(version: str) -> str:
    if not valid_version(version):
        raise ValueError(f"bad version {version!r}")
    return (PRODUCER_EMITTERS / f"{version}.py").read_text(encoding="utf-8")


def write_emitter(version: str, src: str) -> None:
    if not valid_version(version):
        raise ValueError(f"bad version {version!r}")
    (PRODUCER_EMITTERS / f"{version}.py").write_text(src, encoding="utf-8")


def read_handler(version: str) -> str:
    if not valid_version(version):
        raise ValueError(f"bad version {version!r}")
    return (CONSUMER_HANDLERS / f"{version}.py").read_text(encoding="utf-8")


def write_handler(version: str, src: str) -> None:
    if not valid_version(version):
        raise ValueError(f"bad version {version!r}")
    (CONSUMER_HANDLERS / f"{version}.py").write_text(src, encoding="utf-8")


def scaffold_version(version: str) -> dict[str, Path]:
    """Create handler.py + Service yaml + Trigger yaml + emitter.py for a new version.

    Returns a dict of {kind: path} for files written.
    """
    if not valid_version(version):
        raise ValueError(f"bad version {version!r}")

    paths: dict[str, Path] = {}

    # 1. Schema stub (only if missing)
    schema = SCHEMAS / f"{version}.py"
    if not schema.exists():
        schema.write_text(
            f'''from pydantic import BaseModel, Field


class Event{version.upper()}(BaseModel):
    id: str
    kind: str
    value: float = Field(ge=0)
''',
            encoding="utf-8",
        )
        paths["schema"] = schema

    # 2. Producer emitter (only if missing)
    emitter = PRODUCER_EMITTERS / f"{version}.py"
    if not emitter.exists():
        emitter.write_text(
            f'''import random
import uuid

from schemas.{version} import Event{version.upper()}


def sample() -> Event{version.upper()}:
    return Event{version.upper()}(
        id=str(uuid.uuid4()),
        kind=random.choice(["order", "shipment", "return"]),
        value=round(random.uniform(1, 999), 2),
    )
''',
            encoding="utf-8",
        )
        paths["emitter"] = emitter

    # 3. Consumer handler (only if missing) — use the template
    handler = CONSUMER_HANDLERS / f"{version}.py"
    if not handler.exists():
        handler.write_text(
            f'''from typing import Any

from schemas.{version} import Event{version.upper()}


def handle(event: Event{version.upper()}) -> dict[str, Any]:
    return {{"version": "{version}", "id": event.id, "value": event.value}}
''',
            encoding="utf-8",
        )
        paths["handler"] = handler

    # 4. Knative Service yaml
    svc = K8S_SERVICES / f"consumer-{version}.yaml"
    if not svc.exists():
        svc.write_text(_service_yaml(version), encoding="utf-8")
        paths["service"] = svc

    # 5. Trigger yaml
    trig = K8S_TRIGGERS / f"trigger-{version}.yaml"
    if not trig.exists():
        trig.write_text(_trigger_yaml(version), encoding="utf-8")
        paths["trigger"] = trig

    return paths


def remove_version(version: str) -> list[Path]:
    """Remove Service + Trigger YAML for a version. Leaves code in place."""
    if not valid_version(version):
        raise ValueError(f"bad version {version!r}")
    removed: list[Path] = []
    for p in (K8S_SERVICES / f"consumer-{version}.yaml", K8S_TRIGGERS / f"trigger-{version}.yaml"):
        if p.exists():
            p.unlink()
            removed.append(p)
    return removed


def _service_yaml(version: str) -> str:
    return f"""apiVersion: serving.knative.dev/v1
kind: Service
metadata:
  name: consumer-{version}
  namespace: schema-demo
  labels:
    app.kubernetes.io/part-of: demo-schema-versioning
    schema.version: {version}
spec:
  template:
    metadata:
      annotations:
        autoscaling.knative.dev/min-scale: "1"
        autoscaling.knative.dev/max-scale: "3"
    spec:
      containers:
        - image: dev.local/demo-consumer:dev
          imagePullPolicy: Never
          env:
            - name: SCHEMA_VERSION
              value: "{version}"
          ports:
            - containerPort: 8080
          readinessProbe:
            httpGet:
              path: /healthz
              port: 8080
"""


def _trigger_yaml(version: str) -> str:
    return f"""apiVersion: eventing.knative.dev/v1
kind: Trigger
metadata:
  name: trigger-{version}
  namespace: schema-demo
  labels:
    schema.version: {version}
spec:
  broker: schema-broker
  filter:
    attributes:
      schemaversion: {version}
  subscriber:
    ref:
      apiVersion: serving.knative.dev/v1
      kind: Service
      name: consumer-{version}
"""
