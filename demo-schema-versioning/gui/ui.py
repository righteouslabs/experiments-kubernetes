"""Shared UI bits for the FastHTML pages."""

from __future__ import annotations

from typing import Any

from fasthtml.common import H2, Div


def card(title: str, body: Any) -> Any:
    return Div(
        H2(title, style="margin:0 0 .5em 0;font-size:1.1em;color:#333;letter-spacing:.02em"),
        body,
        style="padding:1em;background:#fff;border:1px solid #ddd;border-radius:6px;"
        "box-shadow:0 1px 2px rgba(0,0,0,.04);margin-bottom:1em",
    )


def ok_or_error(msg: str, ok: bool = False) -> str:
    color = "#0a0" if ok else "#c33"
    return f'<div style="color:{color}">{msg}</div>'
