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

import json
import os
import time
from collections import deque
from datetime import datetime
from fasthtml.common import *
from starlette.responses import Response
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
    # Static key stops FastHTML writing .sesskey to cwd — the pod runs as a
    # random non-root UID under the dedicated `dashboard` SA and /app is
    # read-only to it. Sessions are unused by this dashboard.
    secret_key=os.environ.get("DASHBOARD_SECRET_KEY", "demo-dashboard-not-secret"),
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
            #cy { height: 420px; border: 1px solid var(--pico-muted-border-color); border-radius: 8px; }
            .legend { font-size: 0.8rem; color: var(--pico-muted-color); margin-top: 0.5rem; }
            .legend-dot { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin: 0 4px 0 12px; vertical-align: middle; }
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
        P(A("Lattice view (Knative eventing) →", href="/lattice")),
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


# -- Lattice (Knative eventing) live view ------------------------------------
#
# A catch-all Knative Trigger POSTs every broker event here (binary-mode
# CloudEvents). We keep a small in-memory ring buffer + per-edge counters
# and render the topology (ksvcs/revisions/triggers from the k8s API)
# overlaid with live event flow. Everything degrades gracefully when the
# lattice namespace / Knative CRDs don't exist yet.

LATTICE_NS = os.environ.get("LATTICE_NAMESPACE", "lattice-demo")
K8S_API = os.environ.get("K8S_API", "https://kubernetes.default.svc")
SA_DIR = "/var/run/secrets/kubernetes.io/serviceaccount"
ACTIVE_WINDOW_S = 10

lattice_events = deque(maxlen=200)  # newest first: {ceType, ceSource, ceId, orderId, receivedAt}
edge_counters = {}                  # (ceSource, ceType) -> {"count": int, "last": epoch}

KSVC_COLORS = ["#3b82f6", "#10b981", "#8b5cf6", "#f59e0b", "#ec4899", "#06b6d4", "#84cc16"]


@rt("/lattice/events")
async def post(request):
    """CloudEvent sink (binary mode). Must NOT re-emit an event: 202, empty body."""
    body = {}
    try:
        raw = await request.body()
        if raw:
            body = json.loads(raw)
    except Exception:
        body = {}
    h = request.headers
    ce_source = h.get("ce-source", "unknown")
    ce_type = h.get("ce-type", "unknown")
    lattice_events.appendleft({
        "ceType": ce_type,
        "ceSource": ce_source,
        "ceId": h.get("ce-id", ""),
        "orderId": body.get("orderId") if isinstance(body, dict) else None,
        "receivedAt": datetime.now().strftime("%H:%M:%S"),
    })
    c = edge_counters.setdefault((ce_source, ce_type), {"count": 0, "last": 0.0})
    c["count"] += 1
    c["last"] = time.time()
    return Response(status_code=202)


def _k8s_get(path: str):
    """GET a k8s API path with the pod's SA token. None on ANY failure (403/404/no token)."""
    try:
        with open(f"{SA_DIR}/token") as f:
            token = f.read().strip()
        r = httpx.get(f"{K8S_API}{path}",
                      headers={"Authorization": f"Bearer {token}"},
                      verify=f"{SA_DIR}/ca.crt", timeout=3.0)
        return r.json() if r.status_code == 200 else None
    except Exception:
        return None


def _ready(obj: dict) -> bool:
    for c in obj.get("status", {}).get("conditions", []) or []:
        if c.get("type") == "Ready":
            return c.get("status") == "True"
    return False


def _filter_label(attrs: dict) -> str:
    parts = []
    if attrs.get("type"):
        parts.append(attrs["type"].removeprefix("lattice."))
    if attrs.get("source"):
        parts.append(f"from {attrs['source']}")
    parts += [f"{k}={v}" for k, v in sorted(attrs.items()) if k not in ("type", "source")]
    return " ".join(parts) or "all events"


def build_lattice_graph() -> dict:
    """Nodes = revisions (+ broker, external sources, dashboard). Edges = triggers + live producer flow."""
    svc_items = (_k8s_get(f"/apis/serving.knative.dev/v1/namespaces/{LATTICE_NS}/services") or {}).get("items", [])
    rev_items = (_k8s_get(f"/apis/serving.knative.dev/v1/namespaces/{LATTICE_NS}/revisions") or {}).get("items", [])
    trig_items = (_k8s_get(f"/apis/eventing.knative.dev/v1/namespaces/{LATTICE_NS}/triggers") or {}).get("items", [])
    now = time.time()
    counters = {k: dict(v) for k, v in edge_counters.items()}  # snapshot

    deployed = bool(svc_items or rev_items or trig_items or counters)
    if not deployed:
        return {"deployed": False, "elements": []}

    nodes: dict = {}   # id -> {"data": ..., "classes": ...}
    edges: list = []   # {"data": ..., "classes": ...}

    # Per-ksvc color + traffic (revisionName -> "50% #tag")
    traffic_label, traffic_by_ksvc, ksvc_color = {}, {}, {}
    for i, s in enumerate(sorted(svc_items, key=lambda x: x["metadata"]["name"])):
        name = s["metadata"]["name"]
        ksvc_color[name] = KSVC_COLORS[i % len(KSVC_COLORS)]
        for t in s.get("status", {}).get("traffic", []) or []:
            rev = t.get("revisionName")
            if not rev:
                continue
            pct = t.get("percent")
            lbl = " ".join(x for x in [f"{pct}%" if pct is not None else "", f"#{t['tag']}" if t.get("tag") else ""] if x)
            traffic_label[rev] = lbl
            traffic_by_ksvc.setdefault(name, []).append(rev)

    def add_node(nid, label, kind, color="#94a3b8"):
        nodes.setdefault(nid, {"data": {"id": nid, "label": label, "color": color}, "classes": kind})
        return nid

    rev_by_ksvc: dict = {}
    for r in rev_items:
        rname = r["metadata"]["name"]
        parent = r["metadata"].get("labels", {}).get("serving.knative.dev/service", "?")
        rev_by_ksvc.setdefault(parent, []).append(rname)
        sub = " · ".join(x for x in ["ready" if _ready(r) else "not ready", traffic_label.get(rname, "")] if x)
        add_node(rname, f"{rname}\n{sub}", "revision", ksvc_color.get(parent, "#94a3b8"))

    add_node("broker", "lattice\n(broker)", "broker", "#f59e0b")
    add_node("dashboard", "dashboard\n(audit)", "audit", "#64748b")

    def counter_stats(match) -> tuple:
        hits = [c for (s, t), c in counters.items() if match(s, t)]
        return sum(c["count"] for c in hits), max((c["last"] for c in hits), default=0.0)

    # Trigger edges: broker -> subscriber revision(s)
    for tr in trig_items:
        tname = tr["metadata"]["name"]
        spec = tr.get("spec", {}) or {}
        attrs = (spec.get("filter") or {}).get("attributes") or {}
        sub = spec.get("subscriber") or {}
        ref = sub.get("ref") or {}
        uri = sub.get("uri") or ""
        if "dashboard" in (ref.get("name", "") + uri):
            targets = ["dashboard"]
        elif ref.get("name"):
            ksvc = ref["name"]
            targets = traffic_by_ksvc.get(ksvc) or rev_by_ksvc.get(ksvc) or [add_node(ksvc, f"{ksvc}\n(no revisions)", "revision", ksvc_color.get(ksvc, "#94a3b8"))]
        else:
            targets = [add_node(uri or f"trigger:{tname}", uri or tname, "external")]
        cnt, last = counter_stats(lambda s, t: (not attrs.get("type") or attrs["type"] == t)
                                  and (not attrs.get("source") or attrs["source"] == s))
        label = _filter_label(attrs) + (f" ({cnt})" if cnt else "")
        for tgt in targets:
            edges.append({"data": {"id": f"t:{tname}:{tgt}", "source": "broker", "target": tgt, "label": label},
                          "classes": "trigger" + (" active" if now - last < ACTIVE_WINDOW_S else "")})

        # PingSource-like external source: filter source with no live producer and no matching revision
        fsrc = attrs.get("source")
        if fsrc and fsrc not in {s for (s, _t) in counters} and fsrc.rstrip("/").split("/")[-1] not in nodes:
            ext = add_node(f"ext:{fsrc}", fsrc, "external")
            edges.append({"data": {"id": f"s:{fsrc}", "source": ext, "target": "broker", "label": "external source"},
                          "classes": "source"})

    # Producer edges inferred from live events: ce-source -> broker
    for (src, typ), c in counters.items():
        pid = src.rstrip("/").split("/")[-1] or src
        if pid not in nodes:
            pid = add_node(f"ext:{src}", src, "external")
        edges.append({"data": {"id": f"p:{src}:{typ}", "source": pid, "target": "broker",
                               "label": f"{typ.removeprefix('lattice.')} ({c['count']})"},
                      "classes": "producer" + (" active" if now - c["last"] < ACTIVE_WINDOW_S else "")})

    edges = list({e["data"]["id"]: e for e in edges}.values())  # dedupe (shared external sources)

    # Preset positions: producers(0) -> broker(1) -> consumers(2) -> dashboard(3)
    trigger_targets = {e["data"]["target"] for e in edges if e["classes"].startswith("trigger")}
    broker_feeders = {e["data"]["source"] for e in edges if e["data"]["target"] == "broker"}
    per_rank_count: dict = {}
    for nid in sorted(nodes):
        n = nodes[nid]
        kind = n["classes"]
        if kind == "broker":
            rank = 1
        elif kind == "audit":
            rank = 3
        elif nid in broker_feeders and nid not in trigger_targets:
            rank = 0
        else:
            rank = 2
        i = per_rank_count.get(rank, 0)
        per_rank_count[rank] = i + 1
        n["position"] = {"x": 130 + rank * 250, "y": 90 + i * 110}
    nodes["broker"]["position"]["y"] = 90 + max(per_rank_count.get(0, 1), per_rank_count.get(2, 1)) * 55 - 55

    return {"deployed": True, "elements": list(nodes.values()) + edges}


@rt("/lattice/data")
def get():
    graph = build_lattice_graph()
    return {
        "deployed": graph["deployed"],
        "elements": graph["elements"],
        "events": list(lattice_events)[:15],
        "generatedAt": datetime.now().strftime("%H:%M:%S"),
    }


LATTICE_JS = """
(function () {
  let cy = null, sig = null;
  const esc = s => String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
  const CY_STYLE = [
    {selector: 'node', style: {label: 'data(label)', 'text-wrap': 'wrap', 'text-valign': 'center',
      'text-halign': 'center', color: '#fff', 'font-size': '10px', 'text-max-width': '150px',
      shape: 'round-rectangle', width: 'label', height: 'label', padding: '9px',
      'background-color': 'data(color)'}},
    {selector: 'node.broker', style: {shape: 'hexagon', padding: '16px'}},
    {selector: 'node.external', style: {shape: 'ellipse', 'font-size': '8px'}},
    {selector: 'edge', style: {'curve-style': 'bezier', 'target-arrow-shape': 'triangle', width: 1.5,
      'line-color': '#94a3b8', 'target-arrow-color': '#94a3b8', label: 'data(label)', 'font-size': '8px',
      'text-rotation': 'autorotate', 'text-background-color': '#ffffff', 'text-background-opacity': 0.85,
      'text-background-padding': '2px', color: '#334155'}},
    {selector: 'edge.producer, edge.source', style: {'line-style': 'dashed'}},
    {selector: 'edge.active', style: {'line-color': '#10b981', 'target-arrow-color': '#10b981', width: 3}},
  ];
  function render(d) {
    document.getElementById('lat-empty').style.display = d.deployed ? 'none' : 'block';
    document.getElementById('cy').style.display = d.deployed ? 'block' : 'none';
    document.getElementById('lat-event-rows').innerHTML = d.events.length
      ? d.events.map(e => '<tr><td class="mono">' + esc(e.receivedAt) + '</td><td class="mono">' + esc(e.ceType)
          + '</td><td class="mono">' + esc(e.ceSource) + '</td><td class="mono">' + esc(e.orderId ?? '') + '</td></tr>').join('')
      : '<tr><td colspan="4" class="activity-empty">(no events received yet)</td></tr>';
    document.getElementById('lat-refresh').textContent = 'Last refreshed: ' + d.generatedAt;
    if (!d.deployed) { if (cy) { cy.destroy(); cy = null; sig = null; } return; }
    // Structural signature: rebuild+refit only when nodes/edges change, else update in place (no flicker)
    const s = JSON.stringify(d.elements.map(e => [e.data.id, e.data.source || '', e.data.target || '']).sort());
    if (!cy || s !== sig) {
      sig = s;
      if (cy) cy.destroy();
      cy = cytoscape({container: document.getElementById('cy'), elements: d.elements, style: CY_STYLE,
        layout: {name: 'preset'}, userZoomingEnabled: false, userPanningEnabled: false,
        boxSelectionEnabled: false, autoungrabify: true});
      cy.fit(cy.elements(), 30);
    } else {
      d.elements.forEach(e => {
        const el = cy.getElementById(e.data.id);
        if (el.length) { el.data(e.data); el.classes(e.classes || ''); }
      });
    }
  }
  async function poll() {
    try { render(await (await fetch('/lattice/data')).json()); } catch (err) {}
    setTimeout(poll, 3000);
  }
  if (window.cytoscape) poll(); else window.addEventListener('load', poll);
})();
"""


@rt("/lattice")
def get():
    return Title("Knative Lattice"), Main(
        H1("Knative Microservice Lattice"),
        P(A("← Pipeline dashboard", href="/")),
        P(
            "Live topology of the ", Code(LATTICE_NS), " namespace: revisions, the ",
            Code("lattice"), " broker and its triggers from the Kubernetes API, overlaid with ",
            "live CloudEvent flow from the audit sink. Refreshes every 3 seconds.",
        ),
        Div(
            Strong("lattice not deployed yet"),
            f" — waiting for Knative services / triggers in namespace {LATTICE_NS} (or first event).",
            id="lat-empty", cls="activity-empty",
        ),
        Div(id="cy", style="display:none"),
        P(
            Span(cls="legend-dot", style="background:#f59e0b"), "broker",
            Span(cls="legend-dot", style="background:#3b82f6"), "revision (colored per service)",
            Span(cls="legend-dot", style="background:#64748b"), "audit / external",
            Span(cls="legend-dot", style="background:#10b981"), "edge active in last 10s",
            Span(cls="legend-dot", style="background:#94a3b8; height:2px; border-radius:0"), "dashed = inferred from events",
            cls="legend",
        ),
        H2("Recent events"),
        Table(
            Thead(Tr(Th("time"), Th("ce-type"), Th("ce-source"), Th("orderId"))),
            Tbody(Tr(Td("(no events received yet)", colspan="4", cls="activity-empty")), id="lat-event-rows"),
            cls="activity-table",
        ),
        P("", id="lat-refresh", cls="refresh-note"),
        Script(src="https://cdn.jsdelivr.net/npm/cytoscape@3.34.0/dist/cytoscape.min.js"),
        Script(LATTICE_JS),
    )


if __name__ == "__main__":
    serve(host="0.0.0.0", port=5001)
