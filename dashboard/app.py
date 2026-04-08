"""
Pipeline Dashboard — FastHTML app showing real-time state of the
versioned microservice demo.

Polls consumer /status endpoints, Schema Registry, and Kafka metadata
to render a live dashboard that auto-refreshes every 3 seconds.
"""

import os, json, time
from datetime import datetime
from fasthtml.common import *
import httpx

# -- Config (service URLs resolved via K8s DNS or Docker networking) --

CONSUMER_URL = os.environ.get("CONSUMER_URL", "http://consumer:8080")
SCHEMA_REGISTRY_URL = os.environ.get("SCHEMA_REGISTRY_URL", "http://schema-registry:8081")

client = httpx.Client(timeout=3.0)

# -- FastHTML app --

app, rt = fast_app(
    pico=True,
    hdrs=[
        Meta(name="viewport", content="width=device-width, initial-scale=1"),
        Style("""
            :root { --pico-font-size: 16px; }
            body { max-width: 960px; margin: 0 auto; padding: 1rem; }
            .grid-3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 1rem; }
            .card { border: 1px solid var(--pico-muted-border-color); border-radius: 8px; padding: 1rem; }
            .card h3 { margin-top: 0; }
            .stat { font-size: 2rem; font-weight: bold; }
            .stat-label { font-size: 0.85rem; color: var(--pico-muted-color); }
            .consumer-card { border-left: 4px solid #3b82f6; }
            .rule { font-family: monospace; font-size: 0.85rem; background: var(--pico-code-background-color); padding: 2px 6px; border-radius: 4px; display: block; margin: 2px 0; }
            .schema-card { border-left: 4px solid #8b5cf6; }
            .tag { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 0.8rem; margin: 2px; }
            .tag-processed { background: #d1fae5; color: #065f46; }
            .tag-dropped { background: #fee2e2; color: #991b1b; }
            .tag-version { background: #dbeafe; color: #1e40af; }
            .refresh-note { font-size: 0.8rem; color: var(--pico-muted-color); text-align: right; }
            .error { color: var(--pico-del-color); font-size: 0.85rem; }
            table { font-size: 0.9rem; }
        """),
    ],
)


def fetch_consumer_status() -> dict:
    try:
        r = client.get(f"{CONSUMER_URL}/status")
        return r.json()
    except Exception as e:
        return {"appName": "consumer", "error": str(e), "supportedVersions": [], "processedCount": 0, "droppedCount": 0}


def fetch_schema_registry() -> dict:
    try:
        subjects = client.get(f"{SCHEMA_REGISTRY_URL}/subjects").json()
        result = {}
        for subj in subjects:
            versions = client.get(f"{SCHEMA_REGISTRY_URL}/subjects/{subj}/versions").json()
            result[subj] = versions
        return result
    except Exception as e:
        return {"error": str(e)}


def consumer_card(status: dict):
    name = status.get("appName", "consumer")
    versions = status.get("supportedVersions", [])
    rules = status.get("routingRules", [])
    processed = status.get("processedCount", 0)
    dropped = status.get("droppedCount", 0)
    total = processed + dropped
    error = status.get("error")

    pct = f"{(processed / total * 100):.0f}%" if total > 0 else "---"

    return Div(
        H3(f"{name} (multi-version)"),
        P(
            Span(str(processed), cls="stat"),
            Span(" processed", cls="stat-label"),
            Br(),
            Span(f"{pct} hit rate", cls="stat-label"),
            Span(" | ", cls="stat-label"),
            Span(f"{dropped} dropped", cls="stat-label"),
        ) if not error else P(Span(f"Error: {error}", cls="error")),
        P(
            Strong("Supported versions: "),
            *[Span(f"v{v}", cls="tag tag-version") for v in versions],
        ),
        P(
            Strong("Dapr routing rules:"),
            Br(),
            *[Span(r, cls="rule") for r in rules],
        ) if rules else "",
        Div(
            Span(f"processed: {processed}", cls="tag tag-processed"),
            Span(f"dropped: {dropped}", cls="tag tag-dropped"),
        ),
        cls="card consumer-card",
    )


def schema_section(registry: dict):
    if "error" in registry:
        return Div(P(Span(f"Schema Registry: {registry['error']}", cls="error")), cls="card schema-card")

    rows = []
    for subj, versions in registry.items():
        rows.append(Tr(Td(Code(subj)), Td(", ".join(str(v) for v in versions)), Td(str(len(versions)))))

    return Div(
        H3("Schema Registry"),
        P(
            Span(str(len(registry)), cls="stat"),
            Span(f" subject{'s' if len(registry) != 1 else ''}", cls="stat-label"),
        ),
        Table(
            Thead(Tr(Th("Subject"), Th("Versions"), Th("Count"))),
            Tbody(*rows),
        ) if rows else P("No subjects registered"),
        cls="card schema-card",
    )


@rt("/")
def get():
    return Title("Pipeline Dashboard"), Main(
        H1("Versioned Microservice Pipeline"),
        P(
            "Real-time view of the Kafka -> Dapr -> consumer pipeline. ",
            "All versions flow through a ", Strong("single Kafka topic"), ". ",
            "A ", Strong("single consumer"), " handles all schema versions. ",
            "Dapr routes based on ", Code("event.data.schemaVersion"), " in the payload.",
        ),
        Div(id="live", hx_get="/live", hx_trigger="load, every 3s", hx_swap="innerHTML"),
    )


@rt("/live")
def get():
    status = fetch_consumer_status()
    registry = fetch_schema_registry()

    total_processed = status.get("processedCount", 0)
    total_dropped = status.get("droppedCount", 0)
    total_messages = total_processed + total_dropped

    return Div(
        # Summary bar
        Div(
            Div(
                Span(str(total_messages), cls="stat"),
                Span(" total messages seen", cls="stat-label"),
                Br(),
                Span(f"{total_processed} processed | {total_dropped} dropped", cls="stat-label"),
                cls="card",
            ),
            schema_section(registry),
            style="display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; margin-bottom: 1rem;",
        ),
        # Consumer card
        H2("Consumer (handles all schema versions)"),
        consumer_card(status),
        P(f"Last refreshed: {datetime.now().strftime('%H:%M:%S')}", cls="refresh-note"),
    )


if __name__ == "__main__":
    serve(host="0.0.0.0", port=5001)
