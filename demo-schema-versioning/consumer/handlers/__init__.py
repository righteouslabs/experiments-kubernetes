from importlib import import_module
from typing import Any, Callable, cast

from pydantic import BaseModel

HandlerFn = Callable[[BaseModel], dict[str, Any]]


def load_handler(version: str) -> HandlerFn:
    mod = import_module(f"consumer.handlers.{version}")
    fn = getattr(mod, "handle", None)
    if not callable(fn):
        raise RuntimeError(f"handler for {version} missing `handle` function")
    return cast(HandlerFn, fn)
