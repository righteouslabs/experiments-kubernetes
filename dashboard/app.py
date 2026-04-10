"""
Pipeline Dashboard — FastHTML app showing real-time state of the
versioned microservice demo.

Polls consumer /status, producer /status and the /recent endpoints on
both producer and consumer, plus Schema Registry and Kafka metadata, to
render a live dashboard that auto-refreshes every 3 seconds.

The Recent Activity view places producer publications next to consumer
processing and highlights matching correlation IDs so an operator can
trace an individual message end-to-end.
"""

import os
from datetime import datetime
from fasthtml.common import *
import httpx

# -- Config (service URLs resolved via K8s DNS or Docker networking) --

CONSUMER_URL = os.environ.get("CONSUMER_URL", "http://consumer:8080")
PRODUCER_URL = os.environ.get("PRODUCER_URL", "http://producer:8080")
SCHEMA_REGISTRY_URL = os.environ.get("SCHEMA_REGISTRY_URL", "http://schema-registry:8081")

client = httpx.Client(timeout=3.0)

# A small, visually distinct palette used to pair producer rows with
# their matching consumer rows. Each correlation ID that appears on
# both sides is assigned one of these colors deterministically.
MATCH_COLORS = [
    "#fef3c7",  # amber
    "#dcfce7",  # green
    "#e0e7ff",  # indigo
    "#fce7f3",  # pink
    "#cffafe",  # cyan
    "#fee2e2",  # red
    "#ede9fe",  # violet
    "#ffedd5",  # orange
    "#d1fae5",  # emerald
    "#e9d5ff",  # purple
]

# -- FastHTML app --

app, rt = fast_app(
    pico=True,
    hdrs=[
        Meta(name="viewport", content="width=device-width, initial-scale=1"),
        Style("""
            :root { --pico-font-size: 16px; }
            body { max-width: 1100px; margin: 0 auto; padding: 1rem; }
            .grid-3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 1rem; }
            .card { border: 1px solid var(--pico-muted-border-color); border-radius: 8px; padding: 1rem; }
            .card h3 { margin-top: 0; }
            .stat { font-size: 2rem; font-weight: bold; }
            .stat-label { font-size: 0.85rem; color: var(--pico-muted-color); }
            .consumer-card { border-left: 4px solid #3b82f6; }
            .producer-card { border-left: 4px solid #10b981; }
            .rule { font-family: monospace; font-size: 0.85rem; background: var(--pico-code-background-color); padding: 2px 6px; border-radius: 4px; display: block; margin: 2px 0; }
            .schema-card { border-left: 4px solid #8b5cf6; }
            .tag { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 0.8rem; margin: 2px; }
            .tag-processed { background: #d1fae5; color: #065f46; }
            .tag-dropped { background: #fee2e2; color: #991b1b; }
            .tag-version { background: #dbeafe; color: #1e40af; }
            .tag-v1 { background: #e0e7ff; color: #3730a3; }
            .tag-v2 { background: #dcfce7; color: #166534; }
            .tag-v3 { background: #fef3c7; color: #92400e; }
            .refresh-note { font-size: 0.8rem; color: var(--pico-muted-color); text-align: right; }
            .error { color: var(--pico-del-color); font-size: 0.85rem; }
            table { font-size: 0.9rem; }
            .activity-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
            .activity-table { width: 100%; border-collapse: collapse; font-size: 0.82rem; }
            .activity-table th { text-align: left; font-weight: 600; padding: 4px 6px; border-bottom: 1px solid var(--pico-muted-border-color); }
            .activity-table td { padding: 4px 6px; border-bottom: 1px dashed var(--pico-muted-border-color); }
            .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 0.78rem; }
            .seq { color: var(--pico-muted-color); font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
            .match-badge { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 4px; vertical-align: middle; border: 1px solid rgba(0,0,0,0.15); }
            .activity-empty { color: var(--pico-muted-color); font-style: italic; padding: 0.5rem; }
        """),
    ],
)


def fetch_consumer_status() -> dict:
    try:
        r = client.get(f"{CONSUMER_URL}/status")
        return r.json()
    except Exception as e:
        return {"appName": "consumer", "error": str(e), "supportedVersions": [], "processedCount": 0, "droppedCount": 0}


def fetch_producer_status() -> dict:
    try:
        r = client.get(f"{PRODUCER_URL}/status")
        return r.json()
    except Exception as e:
        return {"appName": "producer", "error": str(e), "publishedCount": 0}


def fetch_recent(url: str) -> list:
    try:
        r = client.get(f"{url}/recent")
        data = r.json()
        return data if isinstance(data, list) else []
    except Exception:
        return []


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


def _short(value, n: int = 8) -> str:
    if value is None:
        return ""
    s = str(value)
    return s[:n] if len(s) >= n else s


def _version_tag(v) -> object:
    try:
        iv = int(v)
    except Exception:
        iv = 0
    cls = {1: "tag tag-v1", 2: "tag tag-v2", 3: "tag tag-v3"}.get(iv, "tag tag-version")
    return Span(f"v{iv}", cls=cls)


def _build_match_map(producer_rows: list, consumer_rows: list) -> dict:
    """Map correlation IDs that appear on BOTH sides to a highlight color."""
    producer_cids = {row.get("correlationId") for row in producer_rows if row.get("correlationId")}
    consumer_cids = {row.get("correlationId") for row in consumer_rows if row.get("correlationId")}
    shared = [cid for cid in producer_cids if cid in consumer_cids]
    # Stable color assignment based on insertion order of producer_rows
    ordered = []
    seen = set()
    for row in producer_rows:
        cid = row.get("correlationId")
        if cid in shared and cid not in seen:
            ordered.append(cid)
            seen.add(cid)
    return {cid: MATCH_COLORS[i % len(MATCH_COLORS)] for i, cid in enumerate(ordered)}


def _producer_row(row: dict, match_map: dict):
    cid = row.get("correlationId") or ""
    color = match_map.get(cid)
    style = f"background: {color};" if color else ""
    badge = Span(cls="match-badge", style=f"background: {color};") if color else ""
    return Tr(
        Td(Span(f"#{row.get('seq', '?')}", cls="seq")),
        Td(_version_tag(row.get("version"))),
        Td(Span(_short(row.get("id")), cls="mono")),
        Td(badge, Span(f"cor:{_short(cid)}", cls="mono")),
        Td(row.get("title", "")),
        style=style,
    )


def _consumer_row(row: dict, match_map: dict):
    cid = row.get("correlationId") or ""
    color = match_map.get(cid)
    style = f"background: {color};" if color else ""
    badge = Span(cls="match-badge", style=f"background: {color};") if color else ""
    return Tr(
        Td(_version_tag(row.get("version"))),
        Td(Span(_short(row.get("id")), cls="mono")),
        Td(badge, Span(f"cor:{_short(cid)}", cls="mono")),
        Td(row.get("title", "")),
        style=style,
    )


def recent_activity_section(producer_recent: list, consumer_recent: list,
                            producer_status: dict):
    p_rows = (producer_recent or [])[:10]
    c_rows = (consumer_recent or [])[:10]
    match_map = _build_match_map(p_rows, c_rows)

    producer_error = producer_status.get("error")
    published = producer_status.get("publishedCount", 0)

    producer_body = (
        P(Span(f"Error: {producer_error}", cls="error"))
        if producer_error else (
            Table(
                Thead(Tr(Th("seq"), Th("ver"), Th("id"), Th("correlationId"), Th("title"))),
                Tbody(*[_producer_row(r, match_map) for r in p_rows]) if p_rows
                else Tbody(Tr(Td("(no activity yet)", colspan="5", cls="activity-empty"))),
                cls="activity-table",
            )
        )
    )

    consumer_body = (
        Table(
            Thead(Tr(Th("ver"), Th("id"), Th("correlationId"), Th("title"))),
            Tbody(*[_consumer_row(r, match_map) for r in c_rows]) if c_rows
            else Tbody(Tr(Td("(no activity yet)", colspan="4", cls="activity-empty"))),
            cls="activity-table",
        )
    )

    matched_count = len(match_map)
    legend = (
        P(
            Span(f"{matched_count} correlation id{'s' if matched_count != 1 else ''} matched across producer and consumer",
                 cls="stat-label"),
        )
        if matched_count else
        P(Span("No overlapping correlation ids in the current window — give it a few seconds.", cls="stat-label"))
    )

    return Div(
        H2("Recent Activity"),
        legend,
        Div(
            Div(
                H3("Producer (just published)"),
                P(
                    Span(str(published), cls="stat"),
                    Span(" total published", cls="stat-label"),
                ) if not producer_error else "",
                producer_body,
                cls="card producer-card",
            ),
            Div(
                H3("Consumer (just processed)"),
                consumer_body,
                cls="card consumer-card",
            ),
            cls="activity-grid",
        ),
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
    producer_status = fetch_producer_status()
    registry = fetch_schema_registry()
    producer_recent = fetch_recent(PRODUCER_URL)
    consumer_recent = fetch_recent(CONSUMER_URL)

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
        # Recent activity side-by-side
        recent_activity_section(producer_recent, consumer_recent, producer_status),
        # Consumer card
        H2("Consumer (handles all schema versions)"),
        consumer_card(status),
        P(f"Last refreshed: {datetime.now().strftime('%H:%M:%S')}", cls="refresh-note"),
    )


if __name__ == "__main__":
    serve(host="0.0.0.0", port=5001)
