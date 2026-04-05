# Multi-Version Microservice Demo

Run **multiple versioned microservices** that handle evolving document schemas
on a **single Kafka topic** — with **Dapr** doing content-based routing and
**Schema Registry** tracking schema evolution (including non-additive changes).

> **TL;DR** &mdash; `docker compose up --detach` and open the live dashboard
> to watch versioned consumers process documents in real time.

---

## How It Works

```mermaid
graph LR
    P["Producer<br/>(Java/Spring Boot)"]

    subgraph "Single Kafka Topic"
        T["documents"]
    end

    subgraph SR["Schema Registry"]
        SV["documents-value<br/>v1 · v2 · v3"]
    end

    subgraph "Concurrent Consumers (same image, different config)"
        C1["consumer-v1<br/>SUPPORTED_VERSIONS=1"]
        C2["consumer-v2<br/>SUPPORTED_VERSIONS=2"]
        C3["consumer-v3<br/>SUPPORTED_VERSIONS=3"]
    end

    M[(MongoDB)]
    D["Dashboard<br/>(FastHTML)"]

    P -- "validate → publish" --> T
    P -. "register schemas" .-> SR
    T -- "Dapr routes where<br/>event.data.schemaVersion == 1" --> C1
    T -- "Dapr routes where<br/>event.data.schemaVersion == 2" --> C2
    T -- "Dapr routes where<br/>event.data.schemaVersion == 3" --> C3
    C1 --> M
    C2 --> M
    C3 --> M
    C1 -.- D
    C2 -.- D
    C3 -.- D

    style T fill:#e04e39,color:#fff
    style SR fill:#8b5cf6,color:#fff
    style C1 fill:#3b82f6,color:#fff
    style C2 fill:#10b981,color:#fff
    style C3 fill:#f59e0b,color:#fff
    style M fill:#589636,color:#fff
```

**Key points:**
- **One Kafka topic** for all schema versions — no topic-per-version
- **Dapr inspects the payload** (`event.data.schemaVersion`) and routes to the right consumer
- **Non-additive changes are safe** — V3 changes `tags` from `string[]` to `{name, weight}[]` objects. V2 consumers never see V3 documents.
- **Schema Registry** uses a single subject with NONE compatibility — tracks evolution, doesn't block it

---

## Smart Routing (The Core Mechanism)

```mermaid
sequenceDiagram
    participant P as Producer
    participant SR as Schema Registry
    participant K as Kafka<br/>(documents topic)
    participant D2 as Dapr Sidecar<br/>(consumer-v2)
    participant C as Consumer App<br/>(SUPPORTED_VERSIONS=2)

    Note over P,SR: Startup
    P->>SR: Register v1, v2, v3 under single subject<br/>"documents-value" (NONE compatibility)

    Note over P,C: Publish a v2 document
    P->>P: Validate against v2 schema
    P->>K: Publish plain JSON<br/>{"schemaVersion": 2, "tags": ["urgent"], ...}
    Note over K: Single topic, all versions mixed

    Note over K,C: Consumer-v2 receives message
    K->>D2: Deliver message
    D2->>D2: Parse JSON payload<br/>Evaluate CEL: event.data.schemaVersion == 2? YES
    D2->>C: Forward to /documents
    C->>C: Process v2 document<br/>(tags as plain strings)

    Note over K,C: Consumer-v2 receives a v3 document
    K->>D2: Deliver v3 message
    D2->>D2: Parse JSON payload<br/>Evaluate CEL: event.data.schemaVersion == 2? NO
    D2->>C: Forward to /documents/unhandled
    C-->>D2: {"status": "DROP"}
    Note over D2: Acknowledged, never processed.<br/>V3's weighted-object tags never<br/>reach V2's string-expecting code.
```

### What Makes This "Smart"

The routing rules are **auto-generated from config** — not hardcoded:

```java
// Consumer reads SUPPORTED_VERSIONS env var and generates CEL rules
for (int v : supportedVersions) {
    rules.add(Map.of(
        "match", "event.data.schemaVersion == " + v,  // inspects actual payload
        "path", "/documents"
    ));
}
// Everything else → /documents/unhandled → DROP
```

| What | Where it happens |
|------|-----------------|
| Schema validation | Producer validates against Schema Registry before publishing |
| Version discriminator | `schemaVersion` field in the JSON payload (not Kafka headers, not topic names) |
| Routing decision | Dapr sidecar parses JSON, evaluates CEL expression on `event.data.schemaVersion` |
| Drop non-matching | Dapr forwards to `/documents/unhandled` → app returns `DROP` → acknowledged, not processed |
| Service logic | Consumer only sees payloads for versions it declared. No defensive filtering in app code. |

---

## Schema Evolution (Including Non-Additive Changes)

```mermaid
graph LR
    subgraph V1["V1 — Base"]
        F1["id, title, body<br/>createdAt"]
    end

    subgraph V2["V2 — Adds author + tags"]
        F2["id, title, body, createdAt<br/><b>+ author</b><br/><b>+ tags: string[]</b>"]
    end

    subgraph V3["V3 — NON-ADDITIVE change"]
        F3["id, title, body, createdAt<br/>author<br/><b>tags: {name, weight}[]</b><br/><b>+ priority</b><br/><b>+ metadata{}</b>"]
    end

    V1 -- "additive" --> V2
    V2 -- "BREAKING: tags<br/>changed type" --> V3

    style V1 fill:#dbeafe,stroke:#3b82f6
    style V2 fill:#d1fae5,stroke:#10b981
    style V3 fill:#fef3c7,stroke:#f59e0b
```

**V3 is intentionally non-additive** to demonstrate the pattern:

| Field | V2 | V3 |
|-------|----|----|
| `tags` | `["urgent", "batch"]` (string array) | `[{"name":"urgent","weight":0.8}]` (object array) |

A V2 consumer casting `tags` to `List<String>` would crash on V3 data. But **Dapr routing ensures V2 consumers never see V3 documents** — the breaking change is safe.

All three schemas are registered under **one Schema Registry subject** (`documents-value`) with **NONE compatibility** mode, because Dapr routing provides the safety that would normally come from BACKWARD compatibility rules.

---

## Adding a V4

```mermaid
graph LR
    subgraph "Change (2 files)"
        A1["SchemaRegistrar.java<br/>add SCHEMA_V4"]
        A2["DocumentGenerator.java<br/>add v4 doc builder"]
    end

    subgraph "Deploy"
        B["consumer-v4<br/>SUPPORTED_VERSIONS=4"]
    end

    subgraph "Unchanged"
        C1["Kafka topic"]
        C2["Dapr config"]
        C3["Routing logic"]
        C4["Existing consumers"]
        C5["Schema Registry subject"]
    end

    A1 --> A2 --> B

    style C1 fill:#d1fae5,stroke:#10b981
    style C2 fill:#d1fae5,stroke:#10b981
    style C3 fill:#d1fae5,stroke:#10b981
    style C4 fill:#d1fae5,stroke:#10b981
    style C5 fill:#d1fae5,stroke:#10b981
```

---

## Quick Start

**Prerequisites:** Docker and Docker Compose (v2.20+).

```bash
# Clone with submodules
git clone --recurse-submodules https://github.com/righteouslabs/experiments-kubernetes.git
cd experiments-kubernetes

# Start everything (MicroShift + build + deploy)
docker compose up --detach

# Watch deployment progress
docker compose logs -f cluster-deploy

# Once deployed, open the dashboard
export KUBECONFIG=$(pwd)/microshift-docker-compose/kubeconfig
kubectl -n versioned-demo port-forward svc/dashboard 5001:5001
# → open http://localhost:5001
```

### Watch Logs

```bash
# Producer
kubectl -n versioned-demo logs -l app=producer -c producer -f

# All consumers
kubectl -n versioned-demo logs -l app=consumer -c consumer -f
```

### Check Consumer Stats

```bash
for v in v1 v2 v3; do
  kubectl -n versioned-demo exec deploy/consumer-$v -c consumer -- curl -s localhost:8080/status
done
```

### Standalone Mode (No Kubernetes)

```bash
docker compose -f docker-compose.standalone.yml up --build
# Dashboard: http://localhost:5001
```

### Tear Down

```bash
docker compose down -v
```

---

## Project Structure

```
.
├── docker-compose.yml              # MicroShift + automated deployment
├── docker-compose.standalone.yml   # Plain Docker alternative
├── dashboard/                      # FastHTML live dashboard
│   ├── Dockerfile
│   └── app.py
├── producer/                       # Java producer
│   └── src/.../producer/
│       ├── DocumentGenerator.java  # Publishes plain JSON, Dapr wraps in CloudEvents
│       └── SchemaRegistrar.java    # Registers schemas under single SR subject
├── consumer-service/               # Java consumer (version-configurable)
│   └── src/.../consumer/
│       └── controller/
│           └── SubscriptionController.java  # Dapr CEL routing + version-specific handlers
├── dapr/components/                # Dapr component YAML (Kafka pub/sub, MongoDB state)
├── schemas/                        # JSON Schema files (v1, v2, v3)
├── k8s/                            # Kubernetes manifests
│   ├── infrastructure/             # ZooKeeper, Kafka, Schema Registry, MongoDB
│   ├── dapr/                       # Dapr placement + components ConfigMap
│   ├── services/                   # Producer, consumers (with daprd sidecars), dashboard
│   └── knative/                    # Knative Service definitions (reference)
└── microshift-docker-compose/      # Git submodule: MicroShift in Docker
```

## Key Technologies

| Component | Role |
|-----------|------|
| [Confluent Kafka](https://www.confluent.io/) + [Schema Registry](https://docs.confluent.io/platform/current/schema-registry/) | Single-topic event streaming + schema catalog |
| [Dapr](https://dapr.io/) | Content-based pub/sub routing (CEL on payload), state store |
| [MongoDB](https://www.mongodb.com/) | Document persistence via Dapr state store |
| [MicroShift](https://microshift.io/) | Lightweight OpenShift/K8s (via Docker Compose) |
| Java 17 / Spring Boot 3 | Microservice runtime |
| Python / [FastHTML](https://fastht.ml/) | Live pipeline dashboard |

---

## Design Decisions

### Why NONE Compatibility in Schema Registry?

Schema Registry normally enforces BACKWARD or FORWARD compatibility between
versions of the same subject. We set NONE because **Dapr routing replaces
compatibility as the safety mechanism**:

| Traditional approach | This demo |
|---------------------|-----------|
| BACKWARD compat ensures new consumers read old data | Dapr routing ensures consumers only see their version |
| Limits schema changes to additive-only | Allows breaking changes (V3 changes `tags` type) |
| Safety at serialization layer | Safety at routing layer |

Both are valid — this demo shows the routing-based approach for cases where
versions have fundamentally different business logic.

### Why Not Topic-Per-Version?

Topic proliferation (one topic per schema version) doesn't scale:
- More topics = more partitions = more broker overhead
- Consumer groups multiply
- Operational complexity increases linearly with versions

A single topic with content-based routing scales to any number of versions
without infrastructure changes.

---

## OpenShift / MicroShift Notes

The deployer automatically handles these OpenShift-specific requirements:
- **SCC binding** — `privileged` + `anyuid` SCCs for infrastructure images
- **`enableServiceLinks: false`** — prevents Confluent env var collisions
- **`securityContext.runAsUser: 0`** — Confluent images require root

---

## License

MIT
