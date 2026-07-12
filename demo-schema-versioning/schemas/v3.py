from pydantic import BaseModel, Field


class Tag(BaseModel):
    key: str
    value: str


class EventV3(BaseModel):
    id: str
    kind: str
    value: float = Field(ge=0)
    region: str
    emitted_at_ms: int
    tags: list[Tag] = Field(default_factory=list)
    currency: str = "USD"
