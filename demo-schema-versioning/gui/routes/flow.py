"""Flow viz — bar chart + recent event tail. SSE pushes refresh ticks."""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, AsyncIterator

from fasthtml.common import H3, Div, P, Pre, Span
from starlette.requests import Request
from starlette.responses import HTMLResponse, StreamingResponse

from .. import stats
from ..ui import card

log = logging.getLogger("gui.routes.flow")


def _bar(label: str, count: int, max_count: int) -> Any:
    width_pct = int((count / max_count) * 100) if max_count else 0
    return Div(
        Span(label, style="display:inline-block;width:3em;font-family:monospace"),
        Div(
            style=f"display:inline-block;height:1em;width:{width_pct}%;"
            f"background:linear-gradient(90deg,#4a90e2,#7ec0ee);vertical-align:middle"
        ),
        Span(f" {count}", style="margin-left:.5em;color:#666"),
        style="margin-bottom:.3em",
    )


async def view() -> Any:
    snap = await stats.flow_snapshot(limit=50)
    max_count = max(snap.per_version_count.values(), default=0)
    bars = [
        _bar(version, snap.per_version_count.get(version, 0), max_count)
        for version in sorted(snap.per_version_count.keys())
    ]
    if not bars:
        bars = [P("no consumers ready yet", style="color:#888")]

    rows = []
    for r in snap.recent[:50]:
        rows.append(
            Pre(
                f"{r.get('ts_ms','?')}  {r.get('version','?'):>3}  "
                f"{r.get('_consumer','?'):<14}  id={r.get('id','?')[:12]}...  "
                f"{json.dumps(r.get('result') or {})[:80]}",
                style="margin:0;padding:.1em 0;font-size:11px",
            )
        )

    return card(
        "Flow",
        Div(
            H3("per-version throughput"),
            *bars,
            H3("recent events"),
            Div(*rows, style="max-height:24em;overflow:auto;background:#f7f7f7;padding:.5em"),
            id="flow",
            hx_get="/flow/view",
            hx_trigger="every 2s",
            hx_swap="outerHTML",
        ),
    )


async def view_html(request: Request) -> HTMLResponse:
    return HTMLResponse(str(await view()))


async def _stream() -> AsyncIterator[bytes]:
    while True:
        try:
            snap = await stats.flow_snapshot(limit=50)
            payload = json.dumps(
                {
                    "ts_ms": snap.ts_ms,
                    "per_version_count": snap.per_version_count,
                    "recent": snap.recent[:50],
                }
            )
            yield f"data: {payload}\n\n".encode()
        except Exception as e:  # noqa: BLE001
            yield f"event: error\ndata: {e}\n\n".encode()
        await asyncio.sleep(2)


async def sse(_request: Request) -> StreamingResponse:
    return StreamingResponse(_stream(), media_type="text/event-stream")
