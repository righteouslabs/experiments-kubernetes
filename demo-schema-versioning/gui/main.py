"""Host-side FastAPI + FastHTML control panel for the schema-versioning demo.

Runs on port 8080. Talks to MicroShift via the host kubeconfig; edits files
directly under demo-schema-versioning/{producer,consumer,schemas,k8s} so Tilt
live_update can pick them up.
"""

from __future__ import annotations

import logging
import sys
from pathlib import Path

from fasthtml.common import Div, H1, Meta, Script, Style, Titled, fast_app, serve

THIS_DIR = Path(__file__).resolve().parent
REPO_DIR = THIS_DIR.parent
if str(REPO_DIR) not in sys.path:
    sys.path.insert(0, str(REPO_DIR))

from gui.routes import consumer as r_consumer  # noqa: E402
from gui.routes import flow as r_flow  # noqa: E402
from gui.routes import producer as r_producer  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("gui")

HEAD = (
    Meta(charset="utf-8"),
    Meta(name="viewport", content="width=device-width,initial-scale=1"),
    Script(src="https://unpkg.com/htmx.org@1.9.12"),
    Script(src="https://unpkg.com/htmx.org@1.9.12/dist/ext/sse.js"),
    Style(
        "body{font:14px/1.4 system-ui,sans-serif;background:#f3f3f5;color:#222;margin:0;padding:1.5em}"
        "h1{font-size:1.4em;margin:.2em 0 1em 0}"
        "textarea{font:12px/1.4 ui-monospace,monospace;width:100%;box-sizing:border-box}"
        "input[type=checkbox]{transform:scale(1.2);margin-right:.4em}"
        "table th,table td{border:1px solid #ddd;padding:.3em .6em;text-align:left}"
        ".grid{display:grid;grid-template-columns:1fr 1fr;gap:1em}"
        "@media (max-width:900px){.grid{grid-template-columns:1fr}}"
    ),
)

app, rt = fast_app(hdrs=HEAD, pico=False)


@rt("/")
async def home():
    return Titled(
        "Schema-versioning demo",
        H1("schema-versioning demo"),
        Div(
            Div(
                r_producer.panel(),
                r_consumer.editor(),
                style="display:flex;flex-direction:column;gap:1em",
            ),
            Div(
                await r_consumer.panel(),
                await r_flow.view(),
                style="display:flex;flex-direction:column;gap:1em",
            ),
            cls="grid",
        ),
    )


@rt("/producer/toggle", methods=["POST"])
async def producer_toggle(request):
    return await r_producer.toggle(request)


@rt("/producer/emitter", methods=["POST"])
async def producer_emitter(request):
    return await r_producer.save_emitter(request)


@rt("/consumer/panel", methods=["GET"])
async def consumer_panel(request):
    return await r_consumer.panel_html(request)


@rt("/consumer/handler", methods=["POST"])
async def consumer_handler(request):
    return await r_consumer.save_handler(request)


@rt("/consumer/add", methods=["POST"])
async def consumer_add(request):
    return await r_consumer.add_version(request)


@rt("/consumer/remove", methods=["POST"])
async def consumer_remove(request):
    return await r_consumer.remove_version(request)


@rt("/flow/view", methods=["GET"])
async def flow_view(request):
    return await r_flow.view_html(request)


@rt("/flow/sse", methods=["GET"])
async def flow_sse(request):
    return await r_flow.sse(request)


@rt("/healthz")
def healthz():
    return {"status": "ok"}


if __name__ == "__main__":
    serve(host="0.0.0.0", port=8080)
