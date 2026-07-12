from pydantic import BaseModel, Field


class EventV2(BaseModel):
    id: str
    kind: str
    value: float = Field(ge=0)
    region: str
    emitted_at_ms: int
