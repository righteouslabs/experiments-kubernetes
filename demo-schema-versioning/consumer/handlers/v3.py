from typing import Any

from schemas.v3 import EventV3


def handle(event: EventV3) -> dict[str, Any]:
    tag_map = {t.key: t.value for t in event.tags}
    return {
        "version": "v3",
        "id": event.id,
        "region": event.region,
        "currency": event.currency,
        "tier": tag_map.get("tier", "unknown"),
        "value": event.value,
    }
