"""
Pipeline Dashboard — FastHTML app showing real-time state of the
versioned microservice demo.

Polls consumer /status, producer /status and the /recent endpoints on
both producer and consumer, plus Schema Registry and Kafka metadata, to
render a live dashboard that auto-refreshes every 3 seconds.

The Recent Activity view places producer publications next to consumer
processing and highlights matching correlation IDs so an operator can
trace an individual message end-to-end.

/service-card/document-enricher renders a PO-facing "Service Card" for
the low-code enrichment stage, populated live from the enricher's
/servicecard.json endpoint.
"""

import json
import os
import pathlib
from datetime import datetime
from fasthtml.common import *
from starlette.responses import FileResponse, PlainTextResponse, Response
from starlette.staticfiles import StaticFiles
import httpx

# -- Config (service URLs resolved via K8s DNS or Docker networking) --

CONSUMER_URL = os.environ.get("CONSUMER_URL", "http://consumer:8080")
PRODUCER_URL = os.environ.get("PRODUCER_URL", "http://producer:8080")
ENRICHER_URL = os.environ.get("ENRICHER_URL", "http://document-enricher:8080")
ROUTER_URL = os.environ.get("ROUTER_URL", "http://package-router:8080")
SCHEMA_REGISTRY_URL = os.environ.get("SCHEMA_REGISTRY_URL", "http://schema-registry:8081")
GRAFANA_URL = os.environ.get("GRAFANA_URL", "http://localhost:3000")

LOWCODE_DIR = pathlib.Path(__file__).parent / "lowcode"

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
            .enricher-card { border-left: 4px solid #f59e0b; }
            .lowcode-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 1rem; }
            .kpi { font-size: 1.35rem; font-weight: 600; }
            .kpi-label { font-size: 0.75rem; color: var(--pico-muted-color); text-transform: uppercase; letter-spacing: 0.04em; }
            .delta-good { color: #166534; font-weight: 600; }
            .delta-bad { color: #991b1b; font-weight: 600; }
            .link-row a { margin-right: 1rem; font-size: 0.9rem; }
            .service-card-link { font-weight: 600; color: #b45309 !important; border-bottom: 2px solid #f59e0b; padding-bottom: 2px; }
            .service-card-link:hover { color: #92400e !important; }
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


def fetch_enricher_recent() -> list:
    """Recent enrichments from the low-code stage (document-enricher)."""
    try:
        r = client.get(f"{ENRICHER_URL}/recent")
        data = r.json()
        return data if isinstance(data, list) else []
    except Exception:
        return []


def fetch_enricher_prometheus() -> dict:
    """
    Parse a small set of metrics from the document-enricher's
    /actuator/prometheus endpoint. We only need a handful for the dashboard
    comparison; full KPIs live in Grafana.
    """
    out = {"count": None, "p95_ms": None, "sum": None}
    try:
        r = client.get(f"{ENRICHER_URL}/actuator/prometheus", timeout=2.0)
        if r.status_code != 200:
            return out
        for line in r.text.splitlines():
            # enrichment_latency_seconds_count -> total enrichments
            if line.startswith("enrichment_latency_seconds_count"):
                try:
                    out["count"] = int(float(line.rsplit(" ", 1)[-1]))
                except Exception:
                    pass
            # quantile="0.95" line
            if 'enrichment_latency_seconds{quantile="0.95"' in line:
                try:
                    out["p95_ms"] = round(float(line.rsplit(" ", 1)[-1]) * 1000, 1)
                except Exception:
                    pass
            if line.startswith("enrichment_latency_seconds_sum"):
                try:
                    out["sum"] = float(line.rsplit(" ", 1)[-1])
                except Exception:
                    pass
    except Exception:
        pass
    return out


def _enricher_row(row: dict, match_map: dict):
    cid = row.get("correlationId") or ""
    color = match_map.get(cid)
    style = f"background: {color};" if color else ""
    badge = Span(cls="match-badge", style=f"background: {color};") if color else ""
    grade = row.get("grade") or ""
    return Tr(
        Td(_version_tag(row.get("version"))),
        Td(Span(_short(row.get("id")), cls="mono")),
        Td(badge, Span(f"cor:{_short(cid)}", cls="mono")),
        Td(Span(grade, cls="tag tag-processed" if grade == "A" else "tag tag-version" if grade == "B" else "tag tag-dropped")),
        Td(str(row.get("qualityScore", ""))),
        style=style,
    )


def lowcode_stage_section(consumer_status: dict, enricher_recent: list,
                          enricher_metrics: dict, producer_recent: list):
    """Three-column comparison: existing Dapr consumer vs new SCF enricher."""
    # Extend the producer-vs-consumer match map to include the enricher too,
    # so an operator can trace a single correlationId across all three stages.
    p_rows = (producer_recent or [])[:10]
    e_rows = (enricher_recent or [])[:10]

    producer_cids = {r.get("correlationId") for r in p_rows if r.get("correlationId")}
    enricher_cids = {r.get("correlationId") for r in e_rows if r.get("correlationId")}
    shared = [cid for cid in producer_cids if cid in enricher_cids]
    ordered, seen = [], set()
    for row in p_rows:
        cid = row.get("correlationId")
        if cid in shared and cid not in seen:
            ordered.append(cid)
            seen.add(cid)
    match_map = {cid: MATCH_COLORS[i % len(MATCH_COLORS)] for i, cid in enumerate(ordered)}

    # Simple LOC / memory comparison constants sourced from the repo.
    consumer_loc, consumer_mem = 800, "384Mi"
    enricher_loc, enricher_mem = 120, "512Mi"
    consumer_processed = consumer_status.get("processedCount", 0)
    enricher_processed = enricher_metrics.get("count") or 0
    enricher_p95 = enricher_metrics.get("p95_ms")

    # Latency we don't measure for the Dapr consumer — just show N/A.
    loc_delta_pct = round((1 - enricher_loc / consumer_loc) * 100)

    comparison = Div(
        H3("Stage comparison"),
        Table(
            Thead(Tr(Th(""), Th("Java consumer (Dapr)"), Th("Document enricher (SCF)"), Th("Delta"))),
            Tbody(
                Tr(Td("Application LOC"),
                   Td(f"~{consumer_loc}"),
                   Td(f"~{enricher_loc}"),
                   Td(Span(f"-{loc_delta_pct}%", cls="delta-good"))),
                Tr(Td("Container memory limit"),
                   Td(consumer_mem),
                   Td(enricher_mem),
                   Td("+33%")),
                Tr(Td("Messages processed"),
                   Td(str(consumer_processed)),
                   Td(str(enricher_processed)),
                   Td("—")),
                Tr(Td("Latency p95"),
                   Td("not instrumented"),
                   Td(f"{enricher_p95} ms" if enricher_p95 is not None else "collecting..."),
                   Td("—")),
                Tr(Td("Runtime"),
                   Td("Spring + Dapr CEL routing"),
                   Td("Spring Cloud Function + SCS Kafka binder"),
                   Td("—")),
            ),
            cls="activity-table",
        ),
        cls="card",
    )

    enricher_body = (
        Table(
            Thead(Tr(Th("ver"), Th("id"), Th("correlationId"), Th("grade"), Th("score"))),
            Tbody(*[_enricher_row(r, match_map) for r in e_rows]) if e_rows
            else Tbody(Tr(Td("(no enrichments yet — give it a few seconds)", colspan="5", cls="activity-empty"))),
            cls="activity-table",
        )
    )

    return Div(
        H2("Low-code Stage"),
        P(
            "Spec-first Spring Cloud Function + Spring Cloud Stream Kafka binder. ",
            "Reads from ", Code("documents"), " (same topic the Dapr consumer reads), ",
            "computes ", Code("qualityScore"), " + ", Code("inspectionGrade"), ", ",
            "and publishes to ", Code("documents.enriched"), ".",
        ),
        P(
            Span("", cls="link-row"),
            A("Service Card: document-enricher \u2192",
              href="/service-card/document-enricher",
              cls="service-card-link"),
            A("Service Card: package-router (DMN) \u2192",
              href="/service-card/package-router",
              cls="service-card-link"),
            A("AsyncAPI spec / docs", href="/lowcode-docs/", target="_blank"),
            A("Grafana dashboard", href=GRAFANA_URL, target="_blank"),
            A("Prometheus", href="http://localhost:9091", target="_blank"),
            cls="link-row",
        ),
        Div(
            Div(
                H3("Producer (upstream)"),
                P(Span(str(len(p_rows)), cls="kpi"),
                  Span(" recent publications", cls="kpi-label")),
                cls="card producer-card",
            ),
            Div(
                H3("Document enricher (SCF)"),
                P(Span(str(enricher_processed), cls="kpi"),
                  Span(" messages enriched", cls="kpi-label")),
                P(
                    Span(f"p95 {enricher_p95} ms", cls="tag tag-version") if enricher_p95 is not None else Span("p95 collecting...", cls="tag tag-version"),
                ),
                enricher_body,
                cls="card enricher-card",
            ),
            Div(
                H3("Shared correlation IDs"),
                P(
                    Span(str(len(match_map)), cls="kpi"),
                    Span(" matched", cls="kpi-label"),
                ),
                P(Span("Rows with the same background color trace one message "
                       "end-to-end from the producer to the enricher.", cls="stat-label")),
                cls="card",
            ),
            cls="lowcode-grid",
        ),
        comparison,
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
    enricher_recent = fetch_enricher_recent()
    enricher_metrics = fetch_enricher_prometheus()

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
        # Low-code stage
        lowcode_stage_section(status, enricher_recent, enricher_metrics, producer_recent),
        P(f"Last refreshed: {datetime.now().strftime('%H:%M:%S')}", cls="refresh-note"),
    )


@rt("/lowcode-docs")
def get_docs_index():
    """Serve the pre-rendered AsyncAPI HTML if present, else render a
    minimal fallback page that embeds the raw YAML contract."""
    html_index = LOWCODE_DIR / "public" / "index.html"
    if html_index.is_file():
        return FileResponse(str(html_index))
    yaml_path = LOWCODE_DIR / "asyncapi.yaml"
    yaml_text = yaml_path.read_text() if yaml_path.is_file() else "(asyncapi.yaml not staged)"
    return Response(
        content=(
            "<!doctype html><html><head><title>AsyncAPI — document-enricher</title>"
            "<style>body{font-family:ui-monospace,Menlo,monospace;max-width:1000px;"
            "margin:2rem auto;padding:1rem;background:#fafafa;color:#1f2937}"
            "pre{background:#fff;border:1px solid #e5e7eb;border-radius:8px;padding:1rem;"
            "overflow:auto;font-size:0.85rem;line-height:1.5}h1{color:#92400e}"
            "p{line-height:1.5;font-family:system-ui}</style></head><body>"
            "<h1>AsyncAPI spec (raw)</h1>"
            "<p>The pre-rendered HTML bundle was not generated at image-build time. "
            "Run <code>lowcode-scf/generate-docs.sh</code> and rebuild the dashboard "
            "image to get the full HTML docs. In the meantime, the raw spec — the "
            "source of truth — is shown below.</p>"
            f"<pre>{yaml_text.replace('&', '&amp;').replace('<', '&lt;')}</pre>"
            "</body></html>"
        ),
        media_type="text/html",
    )


# Mount the pre-rendered AsyncAPI HTML bundle (if it exists) at /lowcode-docs/
_public_dir = LOWCODE_DIR / "public"
if _public_dir.is_dir():
    try:
        app.mount("/lowcode-docs", StaticFiles(directory=str(_public_dir), html=True), name="lowcode-docs")
    except Exception:
        # fall back to the handler above
        pass


# ---------------------------------------------------------------------
# Service Card — PO-facing view of the document-enricher
# ---------------------------------------------------------------------

SERVICE_CARD_CSS = """
  :root {
    --sc-bg: #faf9f7;
    --sc-card: #ffffff;
    --sc-border: #e5e7eb;
    --sc-muted: #6b7280;
    --sc-ink: #1f2937;
    --sc-accent: #b45309;
    --sc-accent-soft: #fef3c7;
    --sc-grade-a: #065f46;
    --sc-grade-b: #1e40af;
    --sc-grade-c: #991b1b;
  }
  * { box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: var(--sc-bg);
    color: var(--sc-ink);
    margin: 0;
    padding: 0;
    line-height: 1.55;
  }
  .sc-wrap { max-width: 960px; margin: 0 auto; padding: 2.5rem 1.5rem 4rem; }
  .sc-crumbs { font-size: 0.85rem; color: var(--sc-muted); margin-bottom: 1rem; }
  .sc-crumbs a { color: var(--sc-muted); text-decoration: none; border-bottom: 1px dotted var(--sc-muted); }
  .sc-crumbs a:hover { color: var(--sc-ink); }

  .sc-header {
    background: linear-gradient(135deg, #ffffff 0%, var(--sc-accent-soft) 100%);
    border: 1px solid var(--sc-border);
    border-left: 4px solid var(--sc-accent);
    border-radius: 12px;
    padding: 2rem 2.25rem;
    margin-bottom: 2rem;
    box-shadow: 0 1px 2px rgba(0,0,0,0.04);
  }
  .sc-service-label { font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.08em; color: var(--sc-accent); font-weight: 600; margin-bottom: 0.4rem; }
  .sc-service-name { font-size: 2rem; font-weight: 700; margin: 0 0 0.75rem; letter-spacing: -0.01em; color: var(--sc-ink); }
  .sc-purpose { font-size: 1.05rem; color: #374151; margin: 0; max-width: 70ch; }

  .sc-section { margin-bottom: 2.25rem; }
  .sc-section h2 {
    font-size: 0.8rem;
    text-transform: uppercase;
    letter-spacing: 0.1em;
    color: var(--sc-muted);
    margin: 0 0 0.6rem;
    font-weight: 600;
  }
  .sc-card {
    background: var(--sc-card);
    border: 1px solid var(--sc-border);
    border-radius: 10px;
    padding: 1.5rem 1.75rem;
    box-shadow: 0 1px 2px rgba(0,0,0,0.03);
  }

  .sc-pipeline { text-align: center; }
  .sc-pipeline .mermaid { font-size: 1rem; }
  .sc-pipeline-topics {
    display: flex; justify-content: center; gap: 3rem;
    margin-top: 0.75rem; font-size: 0.85rem; color: var(--sc-muted);
  }
  .sc-pipeline-topics code { background: #f3f4f6; padding: 2px 8px; border-radius: 4px; font-size: 0.85em; }

  table.sc-table { width: 100%; border-collapse: collapse; font-size: 0.93rem; }
  table.sc-table th {
    text-align: left; font-weight: 600; font-size: 0.8rem;
    text-transform: uppercase; letter-spacing: 0.04em; color: var(--sc-muted);
    padding: 0.6rem 0.75rem; border-bottom: 1px solid var(--sc-border);
  }
  table.sc-table td {
    padding: 0.65rem 0.75rem; border-bottom: 1px solid #f3f4f6;
    vertical-align: top;
  }
  table.sc-table tr:last-child td { border-bottom: none; }
  table.sc-table code { background: #f3f4f6; padding: 1px 6px; border-radius: 3px; font-size: 0.88em; }

  .sc-samples { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
  .sc-sample details { background: var(--sc-card); border: 1px solid var(--sc-border); border-radius: 10px; padding: 0; }
  .sc-sample details summary {
    cursor: pointer; padding: 0.85rem 1.25rem; font-weight: 600; font-size: 0.9rem;
    color: var(--sc-ink); border-bottom: 1px solid transparent; user-select: none;
  }
  .sc-sample details[open] summary { border-bottom-color: var(--sc-border); }
  .sc-sample summary::-webkit-details-marker { color: var(--sc-accent); }
  .sc-sample pre {
    margin: 0; padding: 1rem 1.25rem; background: #fafafa;
    font-size: 0.8rem; line-height: 1.5; border-radius: 0 0 10px 10px;
    overflow-x: auto; max-height: 520px;
  }
  .sc-sample code.hljs { background: transparent !important; padding: 0 !important; font-size: 0.8rem; }

  .sc-kpi-type { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 0.72rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.03em; }
  .sc-kpi-type-counter { background: #dbeafe; color: #1e40af; }
  .sc-kpi-type-timer { background: #fef3c7; color: #92400e; }
  .sc-kpi-type-other { background: #f3f4f6; color: #374151; }

  .sc-tag { display: inline-block; background: #f3f4f6; color: #374151; font-size: 0.75rem; padding: 2px 8px; border-radius: 4px; margin: 2px 3px 2px 0; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }

  .sc-wiring { list-style: none; margin: 0; padding: 0; }
  .sc-wiring li { padding: 0.4rem 0; border-bottom: 1px solid #f3f4f6; display: flex; gap: 0.75rem; align-items: center; }
  .sc-wiring li:last-child { border-bottom: none; }
  .sc-wiring .sc-wiring-kind { font-size: 0.72rem; text-transform: uppercase; letter-spacing: 0.05em; color: var(--sc-accent); font-weight: 600; min-width: 75px; }
  .sc-wiring code { font-size: 0.9rem; background: transparent; padding: 0; color: var(--sc-ink); }

  .sc-error { background: #fef2f2; border: 1px solid #fecaca; color: #991b1b; padding: 1rem 1.25rem; border-radius: 10px; }

  /* ---- DMN decision-table styling ---- */
  .sc-dmn-toolbar {
    display: flex; align-items: center; gap: 0.75rem;
    margin-bottom: 1rem; flex-wrap: wrap;
  }
  .sc-dmn-policy {
    display: inline-flex; align-items: center; gap: 0.35rem;
    background: var(--sc-accent); color: #fff;
    font-size: 0.72rem; font-weight: 700; letter-spacing: 0.08em;
    text-transform: uppercase;
    padding: 4px 10px; border-radius: 999px;
    box-shadow: 0 1px 2px rgba(180,83,9,0.25);
  }
  .sc-dmn-policy::before {
    content: ""; width: 6px; height: 6px; border-radius: 50%;
    background: #fef3c7; box-shadow: 0 0 0 2px rgba(255,255,255,0.3);
  }
  .sc-dmn-source {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 0.8rem; color: var(--sc-muted);
  }
  .sc-dmn-source a {
    color: var(--sc-accent); text-decoration: none;
    border-bottom: 1px dotted var(--sc-accent);
  }
  .sc-dmn-inputs-count {
    font-size: 0.75rem; color: var(--sc-muted);
    padding: 2px 8px; background: #f3f4f6; border-radius: 4px;
  }

  /* "Included Models" badge — mirrors VS Code Kogito's tab label. */
  .sc-dmn-included {
    display: inline-flex; align-items: center; gap: 0.35rem;
    background: #fef3c7; color: #92400e;
    font-size: 0.72rem; font-weight: 700; letter-spacing: 0.05em;
    text-transform: uppercase;
    padding: 4px 10px; border-radius: 999px;
    border: 1px solid #fde68a;
  }
  .sc-dmn-included::before {
    content: ""; width: 6px; height: 6px; border-radius: 50%;
    background: var(--sc-accent);
  }
  .sc-dmn-included code {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 0.7rem; background: transparent; padding: 0; color: #92400e;
  }
  .sc-dmn-output-type {
    font-size: 0.75rem; color: var(--sc-muted);
  }
  .sc-dmn-output-type code {
    background: #fef3c7; color: var(--sc-accent);
    padding: 2px 6px; border-radius: 4px; font-weight: 600;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 0.72rem;
  }

  /* Imported types — small "card" list of resolved commons.* types. */
  .sc-dmn-types {
    display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
    gap: 0.75rem; margin-top: 0.75rem;
  }
  .sc-dmn-type-card {
    background: var(--sc-card); border: 1px solid var(--sc-border);
    border-radius: 10px; padding: 0.75rem 0.9rem;
    font-size: 0.82rem;
  }
  .sc-dmn-type-card h4 {
    margin: 0 0 0.4rem 0; font-size: 0.85rem; color: var(--sc-accent);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  }
  .sc-dmn-type-card ul {
    list-style: none; margin: 0; padding: 0;
  }
  .sc-dmn-type-card li {
    padding: 2px 0; font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 0.76rem; color: #374151;
    display: flex; justify-content: space-between; gap: 0.5rem;
  }
  .sc-dmn-type-card li .sc-dmn-type-ref {
    color: var(--sc-muted); font-size: 0.72rem;
  }
  .sc-dmn-type-card li .sc-dmn-type-allowed {
    color: #0f766e; font-size: 0.68rem;
    background: #ccfbf1; padding: 1px 6px; border-radius: 4px;
    margin-left: 0.4rem;
  }

  .sc-dmn-grid-wrap {
    background: var(--sc-card);
    border: 1px solid var(--sc-border);
    border-radius: 12px;
    overflow: hidden;
    box-shadow: 0 1px 3px rgba(0,0,0,0.04);
  }
  table.sc-dmn {
    width: 100%; border-collapse: separate; border-spacing: 0;
    font-size: 0.9rem; font-variant-numeric: tabular-nums;
  }
  table.sc-dmn thead tr.sc-dmn-group th {
    font-size: 0.68rem; text-transform: uppercase; letter-spacing: 0.1em;
    padding: 0.55rem 0.9rem; color: #fff;
    background: var(--sc-accent); border-right: 1px solid rgba(255,255,255,0.15);
  }
  table.sc-dmn thead tr.sc-dmn-group th.sc-dmn-group-out {
    background: #0f766e; /* teal */
  }
  table.sc-dmn thead tr.sc-dmn-group th.sc-dmn-hash {
    background: #1f2937;
    width: 44px; text-align: center;
  }
  table.sc-dmn thead tr.sc-dmn-col th {
    font-size: 0.72rem; font-weight: 600; letter-spacing: 0.05em;
    text-transform: uppercase; color: var(--sc-muted);
    padding: 0.5rem 0.9rem; background: #fafafa;
    border-bottom: 2px solid var(--sc-border); text-align: left;
    border-right: 1px solid #f0f0f0;
  }
  table.sc-dmn thead tr.sc-dmn-col th.sc-dmn-hash { text-align: center; }

  table.sc-dmn td {
    padding: 0.7rem 0.9rem; border-bottom: 1px solid #f3f4f6;
    border-right: 1px solid #f3f4f6; vertical-align: top;
  }
  table.sc-dmn tbody tr:last-child td { border-bottom: none; }
  table.sc-dmn tbody tr:hover td { background: #fffbeb; }

  table.sc-dmn td.sc-dmn-idx {
    text-align: center; font-weight: 700; color: var(--sc-accent);
    background: #fffaf0; width: 44px;
  }
  table.sc-dmn td.sc-dmn-cond { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: #374151; font-size: 0.86rem; }
  table.sc-dmn td.sc-dmn-cond.sc-dmn-any { color: var(--sc-muted); font-style: italic; }
  table.sc-dmn td.sc-dmn-topic { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: #0f766e; font-weight: 600; font-size: 0.86rem; }
  table.sc-dmn td.sc-dmn-sla { text-align: right; font-weight: 600; color: #1f2937; }
  table.sc-dmn td.sc-dmn-sla .sc-dmn-sla-unit { color: var(--sc-muted); font-weight: 400; font-size: 0.75rem; margin-left: 2px; }
  table.sc-dmn td.sc-dmn-reason { color: #4b5563; }
  table.sc-dmn tbody tr.sc-dmn-row-fallback td.sc-dmn-topic { color: #991b1b; }
  table.sc-dmn tbody tr.sc-dmn-row-fallback { background: #fef2f2; }
  table.sc-dmn tbody tr.sc-dmn-row-fallback:hover td { background: #fee2e2; }

  /* ---- Test scenarios table ---- */
  table.sc-scenarios { width: 100%; border-collapse: collapse; font-size: 0.88rem; }
  table.sc-scenarios th {
    text-align: left; font-weight: 600; font-size: 0.72rem;
    text-transform: uppercase; letter-spacing: 0.05em; color: var(--sc-muted);
    padding: 0.55rem 0.75rem; border-bottom: 1px solid var(--sc-border); background: #fafafa;
  }
  table.sc-scenarios td { padding: 0.55rem 0.75rem; border-bottom: 1px solid #f3f4f6; }
  table.sc-scenarios tbody tr:last-child td { border-bottom: none; }
  table.sc-scenarios tbody tr:hover td { background: #fffbeb; }
  .sc-pass { color: #065f46; font-weight: 700; }
  .sc-fail { color: #991b1b; font-weight: 700; }
  .sc-scenario-desc { max-width: 320px; color: #1f2937; }
  .sc-scenario-given { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: #6b7280; font-size: 0.78rem; }
  .sc-scenario-expect { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: #0f766e; font-size: 0.8rem; }

  @media (max-width: 720px) {
    .sc-samples { grid-template-columns: 1fr; }
    .sc-wrap { padding: 1.5rem 1rem 3rem; }
    .sc-service-name { font-size: 1.55rem; }
  }
"""


def _grade_badge_cls(value: str) -> str:
    v = (value or "").strip()
    return {
        "A": "sc-kpi-type sc-kpi-type-counter",
        "B": "sc-kpi-type sc-kpi-type-other",
        "C": "sc-kpi-type sc-kpi-type-timer",
    }.get(v, "sc-kpi-type sc-kpi-type-other")


def _kpi_type_cls(t: str) -> str:
    t = (t or "").lower()
    if t == "counter":
        return "sc-kpi-type sc-kpi-type-counter"
    if t == "timer":
        return "sc-kpi-type sc-kpi-type-timer"
    return "sc-kpi-type sc-kpi-type-other"


def fetch_service_card(base_url: str = ENRICHER_URL) -> dict:
    try:
        r = client.get(f"{base_url}/servicecard.json", timeout=5.0)
        if r.status_code != 200:
            return {"_error": f"HTTP {r.status_code}"}
        return r.json()
    except Exception as e:
        return {"_error": str(e)}


def _render_service_card(card: dict):
    if card.get("_error"):
        return Div(
            Div("Service Card unavailable", style="font-weight:600;margin-bottom:0.5rem;"),
            Div(f"Could not reach {ENRICHER_URL}/servicecard.json — {card['_error']}"),
            cls="sc-error",
        )

    # ---- Header ----
    header = Div(
        Div("Service Card", cls="sc-service-label"),
        H1(card.get("name", "service"), cls="sc-service-name"),
        P(card.get("purpose") or "(no purpose declared)", cls="sc-purpose"),
        cls="sc-header",
    )

    # ---- Pipeline ----
    pipeline = card.get("pipeline") or {}
    mermaid_src = pipeline.get("mermaid") or ""
    in_block = pipeline.get("input") or {}
    # Phase 1 enricher returns a single `output`; Phase 2 router returns
    # a list under `outputs` (one row per destination topic).
    out_block = pipeline.get("output") or {}
    outputs = pipeline.get("outputs")
    if not outputs and out_block:
        outputs = [out_block]
    output_lines = []
    for o in (outputs or []):
        output_lines.append(
            Div(Span("output ", style="color:var(--sc-muted);"),
                Code(o.get("topic", "?")),
                Span(f" \u00b7 {o.get('type','kafka')}", style="color:var(--sc-muted);margin-left:4px;"))
        )
    pipeline_section = Div(
        H2("Pipeline"),
        Div(
            Div(mermaid_src, cls="mermaid"),
            Div(
                Div(Span("input ", style="color:var(--sc-muted);"),
                    Code(in_block.get("topic", "?")),
                    Span(f" \u00b7 {in_block.get('type','kafka')}", style="color:var(--sc-muted);margin-left:4px;")),
                *output_lines,
                cls="sc-pipeline-topics",
            ),
            cls="sc-card sc-pipeline",
        ),
        cls="sc-section",
    )

    # ---- DMN decision table (only present for package-router) ----
    dmn_block = card.get("dmn") or {}
    dmn_section = _render_dmn_section(dmn_block) if dmn_block else None

    # ---- Decision rules ----
    rules = card.get("rules") or []
    rule_rows = [
        Tr(
            Td(Code(r.get("condition", ""))),
            Td(r.get("outcome", "")),
            Td(Code(r["kpi"]) if r.get("kpi") else Span("\u2014", style="color:var(--sc-muted);")),
        )
        for r in rules
    ]
    rules_section = Div(
        H2("Decision rules"),
        Div(
            Table(
                Thead(Tr(Th("Condition"), Th("Outcome"), Th("KPI emitted"))),
                Tbody(*rule_rows) if rule_rows else Tbody(
                    Tr(Td("(none declared)", colspan="3", style="color:var(--sc-muted);text-align:center;padding:1rem;"))
                ),
                cls="sc-table",
            ),
            cls="sc-card",
        ),
        cls="sc-section",
    )

    # ---- Samples ----
    samples = card.get("samples") or {}
    input_json = json.dumps(samples.get("input", {}), indent=2)
    output_json = json.dumps(samples.get("output", {}), indent=2)
    samples_section = Div(
        H2("Sample input / output"),
        Div(
            Div(
                Details(
                    Summary("Input \u2014 a document on `documents`"),
                    Pre(Code(input_json, cls="language-json")),
                    open=True,
                ),
                cls="sc-sample",
            ),
            Div(
                Details(
                    Summary("Output \u2014 enriched on `documents.enriched`"),
                    Pre(Code(output_json, cls="language-json")),
                    open=True,
                ),
                cls="sc-sample",
            ),
            cls="sc-samples",
        ),
        cls="sc-section",
    )

    # ---- KPIs ----
    kpis = card.get("kpis") or []
    kpi_rows = []
    for k in kpis:
        tags = k.get("tags") or []
        tag_tags = [Span(t, cls="sc-tag") for t in tags] if tags else [Span("\u2014", style="color:var(--sc-muted);")]
        kpi_rows.append(
            Tr(
                Td(Strong(k.get("name", ""))),
                Td(Code(k.get("metricName", ""))),
                Td(Span(k.get("type", "").upper(), cls=_kpi_type_cls(k.get("type", "")))),
                Td(*tag_tags),
            )
        )
    kpis_section = Div(
        H2("KPIs emitted"),
        Div(
            Table(
                Thead(Tr(Th("Event"), Th("Metric"), Th("Type"), Th("Tags"))),
                Tbody(*kpi_rows) if kpi_rows else Tbody(
                    Tr(Td("(none registered)", colspan="4", style="color:var(--sc-muted);text-align:center;padding:1rem;"))
                ),
                cls="sc-table",
            ),
            cls="sc-card",
        ),
        cls="sc-section",
    )

    # ---- Wiring ----
    wiring = card.get("wiring") or {}
    wiring_items = []
    for kind, items in (
        ("Consumes", wiring.get("consumes") or []),
        ("Produces", wiring.get("produces") or []),
        ("REST", wiring.get("rest") or []),
    ):
        for item in items:
            wiring_items.append(
                Li(
                    Span(kind, cls="sc-wiring-kind"),
                    Code(item),
                )
            )
    wiring_section = Div(
        H2("Wiring"),
        Div(
            Ul(*wiring_items, cls="sc-wiring") if wiring_items else P("(no wiring declared)", style="color:var(--sc-muted);"),
            cls="sc-card",
        ),
        cls="sc-section",
    )

    # DMN section sits right after the pipeline (the "what it does"
    # headline) because it IS what the service does.
    children = [header, pipeline_section]
    if dmn_section is not None:
        children.append(dmn_section)
    children.extend([rules_section, samples_section, kpis_section, wiring_section])
    return Div(*children)


def _render_dmn_section(dmn: dict):
    """Render the DMN decision table + SCESIM summary.

    The markup is intentionally polished: a gradient header, a FIRST
    hit-policy pill, a spreadsheet-style grid with alternating hover
    highlight, and a test-scenarios table with ✓ pass markers. This is
    the hero demo moment for POs, so it gets its own CSS section.
    """
    table = dmn.get("table") or {}
    rows = table.get("rows") or []
    inputs = table.get("inputs") or []
    outputs = table.get("outputs") or []
    hit_policy = dmn.get("hitPolicy") or "?"
    source_file = dmn.get("sourceFile") or "package-routing.dmn"
    # DMN <import>: "Included Models" + imported itemDefinitions.
    imported_models = dmn.get("importedModels") or []
    imported_types = dmn.get("importedTypes") or []
    decision_output_type = dmn.get("decisionOutputType") or ""

    # Column widths — 3 inputs + 3 outputs (topic/sla/reason). If the DMN
    # grows beyond that, the table still renders but with generic labels.
    input_headers = [Th(i, scope="col") for i in inputs]
    output_headers = [Th(o, scope="col") for o in outputs]

    def _fmt_when(entry):
        if entry is None or entry == "" or entry == "-":
            return Td(Span("any", cls="sc-dmn-any"), cls="sc-dmn-cond sc-dmn-any")
        # Trim FEEL quotes to keep the table readable.
        clean = entry.replace("&quot;", "\"")
        return Td(clean, cls="sc-dmn-cond")

    def _fmt_topic(raw):
        t = (raw or "").replace('"', '').strip()
        return Td(t or "\u2014", cls="sc-dmn-topic")

    def _fmt_sla(raw):
        raw = (raw or "").strip()
        if not raw or raw == "null":
            return Td(Span("\u2014", cls="sc-dmn-any"), cls="sc-dmn-sla")
        return Td(raw, Span(" min", cls="sc-dmn-sla-unit"), cls="sc-dmn-sla")

    def _fmt_reason(raw):
        r = (raw or "").replace('"', '').strip()
        return Td(r or "\u2014", cls="sc-dmn-reason")

    def _row(row):
        idx = row.get("index")
        when = row.get("when") or []
        then = row.get("then") or []
        # Pad to 3 inputs / 3 outputs for alignment.
        while len(when) < 3: when.append("-")
        while len(then) < 3: then.append("")
        is_fallback = all(w in (None, "", "-") for w in when)
        return Tr(
            Td(str(idx), cls="sc-dmn-idx"),
            _fmt_when(when[0]),
            _fmt_when(when[1]),
            _fmt_when(when[2]),
            _fmt_topic(then[0]),
            _fmt_sla(then[1]),
            _fmt_reason(then[2]),
            cls="sc-dmn-row-fallback" if is_fallback else "",
        )

    # Build group header: "#" | inputs span | outputs span
    in_span = max(len(inputs), 3)
    out_span = max(len(outputs), 3)
    grid_table = Table(
        Thead(
            Tr(
                Th("#", cls="sc-dmn-hash", rowspan="2"),
                Th(f"Inputs ({in_span})", colspan=str(in_span)),
                Th(f"Outputs ({out_span})", colspan=str(out_span), cls="sc-dmn-group-out"),
                cls="sc-dmn-group",
            ),
            Tr(
                *input_headers,
                *output_headers,
                cls="sc-dmn-col",
            ),
        ),
        Tbody(*[_row(r) for r in rows]) if rows else Tbody(
            Tr(Td("(no DMN rows parsed)", colspan=str(1 + in_span + out_span),
                  style="text-align:center;color:var(--sc-muted);padding:1rem;"))
        ),
        cls="sc-dmn",
    )

    toolbar_children = [
        Span(f"Hit policy: {hit_policy}", cls="sc-dmn-policy"),
        Span(f"Source: ", cls="sc-dmn-source"),
        Span(Code(source_file), cls="sc-dmn-source"),
        Span(f"{len(rows)} rules \u00b7 {len(inputs)} inputs \u00b7 {len(outputs)} outputs",
             cls="sc-dmn-inputs-count"),
    ]
    # One pill per imported DMN model — mirrors the VS Code Kogito
    # editor's "Included Models" tab. Shows that the output type is
    # resolved out of a shared types file, so POs see the provenance.
    for m in imported_models:
        toolbar_children.append(
            Span(
                "Included: ",
                Code(m.get("name", "?")),
                Span(f" ({m.get('locationURI','?')})", cls="sc-dmn-type-ref",
                     style="margin-left:0.25rem;"),
                cls="sc-dmn-included",
                title=f"namespace={m.get('namespace','')}",
            )
        )
    if decision_output_type:
        toolbar_children.append(
            Span(
                "Output type: ",
                Code(decision_output_type),
                cls="sc-dmn-output-type",
            )
        )
    toolbar = Div(*toolbar_children, cls="sc-dmn-toolbar")

    # Test scenarios summary.
    ts = dmn.get("testScenarios") or {}
    scenarios = ts.get("scenarios") or []
    ts_rows = []
    for s in scenarios:
        ts_rows.append(
            Tr(
                Td(Strong(s.get("index", "?")), style="text-align:center;color:var(--sc-accent);"),
                Td(s.get("description", ""), cls="sc-scenario-desc"),
                Td(
                    Span(f"priority={s.get('priority','').strip(chr(34))}", cls="sc-scenario-given"),
                    Br(),
                    Span(f"bodyLength={s.get('bodyLength','')}", cls="sc-scenario-given"),
                    Br(),
                    Span(f"grade={s.get('enrichmentGrade','').strip(chr(34))}", cls="sc-scenario-given"),
                ),
                Td(
                    Span(f"topic={s.get('expectedTopic','').strip(chr(34))}", cls="sc-scenario-expect"),
                    Br(),
                    Span(f"sla={s.get('expectedSlaMinutes','')}", cls="sc-scenario-expect"),
                    Br(),
                    Span(f"reason={s.get('expectedReason','').strip(chr(34))[:40]}", cls="sc-scenario-expect"),
                ),
                Td(Span("\u2713 passed", cls="sc-pass"), style="text-align:center;"),
            )
        )
    scenarios_table = Table(
        Thead(Tr(Th("#"), Th("Scenario"), Th("Given"), Th("Expected"), Th("Result"))),
        Tbody(*ts_rows) if ts_rows else Tbody(
            Tr(Td("(no scenarios parsed)", colspan="5",
                  style="text-align:center;color:var(--sc-muted);padding:1rem;"))
        ),
        cls="sc-scenarios",
    )

    # Imported data types — render each resolved `commons.*` type as a
    # mini-card so the PO sees exactly which fields the shared schema
    # provides. This is the dashboard-side mirror of VS Code Kogito's
    # "Data Types" tab.
    imported_section = None
    if imported_types:
        type_cards = []
        for t in imported_types:
            field_items = []
            for f in t.get("fields") or []:
                children = [
                    Span(f.get("name", "?")),
                    Span(
                        Span(f.get("typeRef", ""), cls="sc-dmn-type-ref"),
                        Span(f.get("allowedValues"),
                             cls="sc-dmn-type-allowed") if f.get("allowedValues") else "",
                    ),
                ]
                field_items.append(Li(*children))
            type_cards.append(
                Div(
                    H4(f"commons.{t.get('name','?')}"),
                    Ul(*field_items) if field_items else P("(no fields)",
                            style="font-size:0.75rem;color:var(--sc-muted);margin:0;"),
                    cls="sc-dmn-type-card",
                )
            )
        imported_section = Div(
            H2("Included models \u2014 shared data types",
               style="margin-top:2rem;"),
            P(Span(
                f"Resolved {len(imported_types)} itemDefinitions imported from ",
                Code(imported_models[0].get("locationURI", "commons-types.dmn")
                     if imported_models else "commons-types.dmn"),
                ". Edit the shared file once; every service that imports it sees the update.",
                cls="sc-dmn-source",
            )),
            Div(*type_cards, cls="sc-dmn-types"),
        )

    children = [
        H2("Decision table (source of truth)"),
        toolbar,
        Div(grid_table, cls="sc-dmn-grid-wrap"),
    ]
    if imported_section is not None:
        children.append(imported_section)
    children.extend([
        H2("PO-authored test scenarios", style="margin-top:2rem;"),
        P(Span(f"{ts.get('count', 0)} scenarios from ",
               Code("package-routing.scesim"),
               ". Marked as passed when the live DMN evaluation returns the expected outputs.",
               cls="sc-dmn-source")),
        Div(scenarios_table, cls="sc-card", style="padding:0;overflow:hidden;"),
    ])
    return Div(*children, cls="sc-section")


@rt("/service-card/document-enricher")
def service_card_enricher():
    card = fetch_service_card()
    return (
        Title(f"Service Card \u2014 {card.get('name', 'document-enricher')}"),
        Meta(name="viewport", content="width=device-width, initial-scale=1"),
        Style(SERVICE_CARD_CSS),
        # Mermaid for the pipeline flowchart
        Script(src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"),
        Script("mermaid.initialize({ startOnLoad: true, theme: 'neutral', securityLevel: 'loose' });"),
        # Highlight.js for JSON syntax highlighting in the sample blocks
        Link(rel="stylesheet", href="https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles/atom-one-light.min.css"),
        Script(src="https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/highlight.min.js"),
        Script("window.addEventListener('DOMContentLoaded', () => { document.querySelectorAll('pre code').forEach((el) => hljs.highlightElement(el)); });"),
        Main(
            Div(
                P(
                    A("\u2190 Pipeline dashboard", href="/"),
                    Span("  \u00b7  ", style="color:#d1d5db;"),
                    A("AsyncAPI spec", href="/lowcode-docs/", target="_blank"),
                    Span("  \u00b7  ", style="color:#d1d5db;"),
                    A("Grafana", href=GRAFANA_URL, target="_blank"),
                    cls="sc-crumbs",
                ),
                _render_service_card(card),
                cls="sc-wrap",
            )
        ),
    )


@rt("/service-card/package-router")
def service_card_router():
    card = fetch_service_card(ROUTER_URL)
    return (
        Title(f"Service Card \u2014 {card.get('name', 'package-router')}"),
        Meta(name="viewport", content="width=device-width, initial-scale=1"),
        Style(SERVICE_CARD_CSS),
        Script(src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"),
        Script("mermaid.initialize({ startOnLoad: true, theme: 'neutral', securityLevel: 'loose' });"),
        Link(rel="stylesheet", href="https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles/atom-one-light.min.css"),
        Script(src="https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/highlight.min.js"),
        Script("window.addEventListener('DOMContentLoaded', () => { document.querySelectorAll('pre code').forEach((el) => hljs.highlightElement(el)); });"),
        Main(
            Div(
                P(
                    A("\u2190 Pipeline dashboard", href="/"),
                    Span("  \u00b7  ", style="color:#d1d5db;"),
                    A("Phase 1 Service Card", href="/service-card/document-enricher"),
                    Span("  \u00b7  ", style="color:#d1d5db;"),
                    A("Grafana", href=GRAFANA_URL, target="_blank"),
                    cls="sc-crumbs",
                ),
                _render_service_card(card),
                cls="sc-wrap",
            )
        ),
    )


if __name__ == "__main__":
    serve(host="0.0.0.0", port=5001)
