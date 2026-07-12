from typing import Any

from schemas.v1 import EventV1


def handle(event: EventV1) -> dict[str, Any]:
    return {"version": "v1", "id": event.id, "value_doubled": event.value * 2}
