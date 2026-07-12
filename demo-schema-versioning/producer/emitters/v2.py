import random
import time
import uuid

from schemas.v2 import EventV2


def sample() -> EventV2:
    return EventV2(
        id=str(uuid.uuid4()),
        kind=random.choice(["order", "shipment", "return"]),
        value=round(random.uniform(1, 999), 2),
        region=random.choice(["us-east", "us-west", "eu-central", "ap-south"]),
        emitted_at_ms=int(time.time() * 1000),
    )
