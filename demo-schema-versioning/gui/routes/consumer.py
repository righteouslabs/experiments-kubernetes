"""Consumer panel + editor + add-version."""

from __future__ import annotations

import logging
import subprocess
from typing import Any

from fasthtml.common import (
    H3,
    Button,
    Div,
    Form,
    Input,
    Label,
    P,
    Table,
    Tbody,
    Td,
    Textarea,
    Th,
    Thead,
    Tr,
)
from starlette.requests import Request
from starlette.responses import HTMLResponse

from gui import fs, kube, stats
from gui.formutil import form_str
from gui.ui import card, ok_or_error

log = logging.getLogger("gui.routes.consumer")


async def panel() -> Any:
    try:
        rows = await stats.gather_stats()
    except Exception as e:  # noqa: BLE001
        return card("Consumers", P(f"stats unavailable: {e}"))

    body_rows = []
    for s in rows:
        accepted = s.counters.get("accepted", 0)
        received = s.counters.get("received", 0)
        rejected = s.counters.get("rejected_version", 0) + s.counters.get("rejected_invalid", 0)
        body_rows.append(
            Tr(
                Td(s.ksvc_name),
                Td(s.version),
                Td("ready" if s.ready else "pending"),
                Td(str(received)),
                Td(str(accepted)),
                Td(str(rejected)),
                Td(s.error or "", style="color:#c33"),
            )
        )

    table = Table(
        Thead(Tr(Th("name"), Th("version"), Th("status"), Th("received"), Th("accepted"), Th("rejected"), Th("error"))),
        Tbody(*body_rows),
        cellpadding="6",
        style="border-collapse:collapse;font-family:monospace",
    )

    return card(
        "Consumers",
        Div(
            table,
            Div(
                Form(
                    Label("new version: ", Input(type="text", name="version", placeholder="v4", size=6)),
                    Button("Add", type="submit"),
                    hx_post="/consumer/add",
                    hx_target="#consumer-editor",
                    hx_swap="outerHTML",
                ),
                style="margin-top:1em",
            ),
            id="consumer-panel",
            hx_get="/consumer/panel",
            hx_trigger="every 2s",
            hx_swap="outerHTML",
        ),
    )


def editor() -> Any:
    versions = fs.list_versions_on_disk()
    blocks = []
    for v in versions:
        try:
            src = fs.read_handler(v)
        except FileNotFoundError:
            continue
        blocks.append(
            Div(
                H3(f"handlers/{v}.py"),
                Form(
                    Textarea(src, name="src", rows=10, cols=80, spellcheck="false"),
                    Input(type="hidden", name="version", value=v),
                    Button("Save", type="submit"),
                    Button(
                        "Remove",
                        type="submit",
                        formaction="/consumer/remove",
                        formmethod="post",
                        hx_post="/consumer/remove",
                        hx_target="#consumer-editor",
                        hx_swap="outerHTML",
                        hx_confirm=f"remove version {v}?",
                        style="margin-left:1em;color:#c33",
                    ),
                    method="post",
                    action="/consumer/handler",
                    hx_post="/consumer/handler",
                    hx_target="#consumer-editor",
                    hx_swap="outerHTML",
                ),
            )
        )
    return card("Consumer editor", Div(*blocks, id="consumer-editor"))


async def panel_html(_request: Request) -> HTMLResponse:
    return HTMLResponse(str(await panel()))


async def save_handler(request: Request) -> HTMLResponse:
    form = await request.form()
    version = form_str(form, "version").strip()
    src = form_str(form, "src")
    if not fs.valid_version(version):
        return HTMLResponse(ok_or_error(f"invalid version: {version!r}"))
    fs.write_handler(version, src)
    return HTMLResponse(str(editor()))


async def add_version(request: Request) -> HTMLResponse:
    form = await request.form()
    version = form_str(form, "version").strip()
    if not fs.valid_version(version):
        return HTMLResponse(ok_or_error(f"invalid version: {version!r}"))
    try:
        fs.scaffold_version(version)
        # apply yaml so Knative picks it up; Tilt will also do this on next sync
        kubectl_apply(fs.K8S_SERVICES / f"consumer-{version}.yaml")
        kubectl_apply(fs.K8S_TRIGGERS / f"trigger-{version}.yaml")
    except Exception as e:  # noqa: BLE001
        return HTMLResponse(ok_or_error(f"add failed: {e}"))
    return HTMLResponse(str(editor()))


async def remove_version(request: Request) -> HTMLResponse:
    form = await request.form()
    version = form_str(form, "version").strip()
    if not fs.valid_version(version):
        return HTMLResponse(ok_or_error(f"invalid version: {version!r}"))
    try:
        kubectl_delete("ksvc", f"consumer-{version}")
        kubectl_delete("trigger.eventing.knative.dev", f"trigger-{version}")
        fs.remove_version(version)
    except Exception as e:  # noqa: BLE001
        return HTMLResponse(ok_or_error(f"remove failed: {e}"))
    return HTMLResponse(str(editor()))


def kubectl_apply(path) -> None:
    subprocess.run(["kubectl", "apply", "-n", kube.NAMESPACE, "-f", str(path)], check=True, timeout=30)


def kubectl_delete(kind: str, name: str) -> None:
    subprocess.run(
        ["kubectl", "delete", kind, name, "-n", kube.NAMESPACE, "--ignore-not-found=true"],
        check=False,
        timeout=30,
    )
