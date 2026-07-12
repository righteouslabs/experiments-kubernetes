import random
import time
import uuid

from schemas.v3 import EventV3, Tag


def sample() -> EventV3:
    return EventV3(
        id=str(uuid.uuid4()),
        kind=random.choice(["order", "shipment", "return", "audit"]),
        value=round(random.uniform(1, 9999), 2),
        region=random.choice(["us-east", "us-west", "eu-central", "ap-south"]),
        emitted_at_ms=int(time.time() * 1000),
        tags=[
            Tag(key="channel", value=random.choice(["web", "mobile", "kiosk"])),
            Tag(key="tier", value=random.choice(["bronze", "silver", "gold"])),
        ],
        currency=random.choice(["USD", "EUR", "GBP", "JPY"]),
    )
