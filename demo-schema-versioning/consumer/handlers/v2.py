from typing import Any

from schemas.v2 import EventV2


def handle(event: EventV2) -> dict[str, Any]:
    return {
        "version": "v2",
        "id": event.id,
        "region": event.region,
        "value": event.value,
    }
