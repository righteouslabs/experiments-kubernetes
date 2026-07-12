from typing import Literal

from pydantic import BaseModel

from .v1 import EventV1
from .v2 import EventV2
from .v3 import EventV3

SchemaVersion = Literal["v1", "v2", "v3"]

REGISTRY: dict[str, type[BaseModel]] = {
    "v1": EventV1,
    "v2": EventV2,
    "v3": EventV3,
}


def model_for(version: str) -> type[BaseModel]:
    try:
        return REGISTRY[version]
    except KeyError as e:
        raise ValueError(f"unknown schema version: {version}") from e


def versions() -> list[str]:
    return sorted(REGISTRY.keys())
