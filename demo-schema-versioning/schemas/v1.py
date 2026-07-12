from pydantic import BaseModel, Field


class EventV1(BaseModel):
    id: str
    kind: str
    value: float = Field(ge=0)
