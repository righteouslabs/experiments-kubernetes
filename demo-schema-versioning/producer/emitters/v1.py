import random
import uuid

from schemas.v1 import EventV1


def sample() -> EventV1:
    return EventV1(
        id=str(uuid.uuid4()),
        kind=random.choice(["order", "shipment", "return"]),
        value=round(random.uniform(1, 999), 2),
    )
