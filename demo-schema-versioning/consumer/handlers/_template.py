"""Template handler for new versions.

Copy this file to `handlers/<vN>.py` and update imports + logic.
The GUI's "add version" button does this automatically.
"""

from typing import Any

# from schemas.vN import EventVN


def handle(event: Any) -> dict[str, Any]:  # noqa: ARG001
    return {"version": "vN", "ok": True}
