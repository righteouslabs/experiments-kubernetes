"""Small helpers to extract clean str / list[str] from Starlette FormData,
which is typed as `Mapping[str, UploadFile | str]`.
"""

from __future__ import annotations

from typing import Any


def form_str(form: Any, key: str, default: str = "") -> str:
    v = form.get(key, default)
    return v if isinstance(v, str) else default


def form_strlist(form: Any, key: str) -> list[str]:
    if hasattr(form, "getlist"):
        return [v for v in form.getlist(key) if isinstance(v, str)]
    v = form.get(key)
    if isinstance(v, str):
        return [v]
    return []
