# Schema-versioning demo

One Kafka topic (`events`). One producer image emitting CloudEvents with
attribute `schemaversion ∈ {v1, v2, v3}`. One consumer image, parameterized
by `SCHEMA_VERSION`, deployed as N Knative Services. A KafkaSource ingests
the topic into a Knative Kafka Broker; per-version Triggers fan events out
to the right consumer.

## Topology

```
   producer
      │   (Kafka headers carry CE attributes, body is JSON)
      ▼
   topic: events
      │
      ▼
   KafkaSource ──▶ Broker (Kafka class) ──▶ Triggers (filter schemaversion)
                                                 │      │      │
                                                 ▼      ▼      ▼
                                          consumer-v1 v2 v3 …  (Knative Services)
```

Adding a version = adding one Trigger + one Knative Service. The GUI does
this with a single click.

## Run

From the repo root:

```bash
tilt up
```

Then open <http://localhost:8080> for the control GUI and
<http://localhost:10350> for the Tilt UI.

## Layout

| Path | Purpose |
|---|---|
| `schemas/{v1,v2,v3}.py` | Pydantic models shared by producer + consumer |
| `producer/main.py` | CloudEvents-over-Kafka emitter |
| `producer/emitters/*.py` | Per-version sample factories |
| `consumer/main.py` | FastAPI receiver, validates against `$SCHEMA_VERSION` |
| `consumer/handlers/*.py` | Per-version business logic |
| `gui/main.py` | FastAPI + FastHTML control panel |
| `k8s/redpanda/` | Single-broker Redpanda + topic-create Job |
| `k8s/broker/` | Knative Kafka Broker + config ConfigMap |
| `k8s/sources/` | KafkaSource → Broker |
| `k8s/services/` | One Knative Service per version |
| `k8s/triggers/` | One Trigger per version |
| `k8s/producer/` | Producer Deployment |

## GUI panels

1. **Producer** — toggle `ACTIVE_VERSIONS` live; inline-edit emitters.
2. **Consumers** — table of every Knative Service in `schema-demo` with
   live counters from each consumer's `/stats`.
3. **Consumer editor** — edit `handlers/<v>.py`; "Add version" button
   scaffolds schema + emitter + handler + Service + Trigger YAML.
4. **Flow** — bar chart of per-version throughput + tail of last 50 events.

All panels poll every 2s via HTMX.
