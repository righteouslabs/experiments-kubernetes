"""Producer panel routes."""

from __future__ import annotations

import logging
from typing import Any

from fasthtml.common import (
    H3,
    Button,
    Div,
    Form,
    Input,
    Label,
    Li,
    P,
    Span,
    Textarea,
    Ul,
)
from starlette.requests import Request
from starlette.responses import HTMLResponse

from gui import fs, kube
from gui.formutil import form_str, form_strlist
from gui.ui import card, ok_or_error

log = logging.getLogger("gui.routes.producer")


def panel() -> Any:
    try:
        active = kube.get_producer_active_versions()
    except Exception as e:  # noqa: BLE001
        return card("Producer", P(f"unable to read producer deployment: {e}"))

    on_disk = fs.list_versions_on_disk()

    toggles = []
    for v in sorted(set(on_disk) | set(active)):
        checked = "checked" if v in active else ""
        toggles.append(
            Li(
                Label(
                    Input(
                        type="checkbox",
                        name="versions",
                        value=v,
                        checked=bool(checked),
                        hx_post="/producer/toggle",
                        hx_trigger="change",
                        hx_target="#producer-panel",
                        hx_swap="outerHTML",
                    ),
                    f" {v}",
                ),
                style="display:inline-block;margin-right:1em",
            )
        )

    emitter_blocks = []
    for v in on_disk:
        try:
            src = fs.read_emitter(v)
        except FileNotFoundError:
            continue
        emitter_blocks.append(
            Div(
                H3(f"emitters/{v}.py"),
                Form(
                    Textarea(src, name="src", rows=10, cols=80, spellcheck="false"),
                    Input(type="hidden", name="version", value=v),
                    Button("Save", type="submit"),
                    method="post",
                    action="/producer/emitter",
                    hx_post="/producer/emitter",
                    hx_target="#producer-panel",
                    hx_swap="outerHTML",
                ),
            )
        )

    return card(
        "Producer",
        Div(
            P(Span("ACTIVE_VERSIONS: ", style="color:#888"), Span(",".join(active) or "(none)")),
            Ul(*toggles, style="list-style:none;padding:0"),
            *emitter_blocks,
            id="producer-panel",
        ),
    )


async def toggle(request: Request) -> HTMLResponse:
    form = await request.form()
    versions = [v for v in form_strlist(form, "versions") if fs.valid_version(v)]
    try:
        kube.set_producer_active_versions(versions)
    except Exception as e:  # noqa: BLE001
        return HTMLResponse(ok_or_error(f"toggle failed: {e}"))
    return HTMLResponse(str(panel()))


async def save_emitter(request: Request) -> HTMLResponse:
    form = await request.form()
    version = form_str(form, "version").strip()
    src = form_str(form, "src")
    if not fs.valid_version(version):
        return HTMLResponse(ok_or_error(f"invalid version: {version!r}"))
    fs.write_emitter(version, src)
    return HTMLResponse(str(panel()))
