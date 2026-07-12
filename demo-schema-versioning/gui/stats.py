"""Poll consumer /stats endpoints — works against either KSVC URL or the
in-cluster Service via port-forward fallback (host-side execution).
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Any

import httpx

from . import kube

log = logging.getLogger("gui.stats")


@dataclass
class ConsumerStat:
    version: str
    ksvc_name: str
    ready: bool
    url: str | None
    counters: dict[str, int] = field(default_factory=dict)
    recent: list[dict[str, Any]] = field(default_factory=list)
    error: str | None = None


async def _fetch_one(client: httpx.AsyncClient, ksvc: dict[str, Any]) -> ConsumerStat:
    name = str(ksvc.get("metadata", {}).get("name", "?"))
    env = kube.service_env(ksvc)
    version = env.get("SCHEMA_VERSION") or name.replace("consumer-", "") or "?"
    url = kube.service_url(ksvc)
    ready = kube.service_ready(ksvc)
    stat = ConsumerStat(version=version, ksvc_name=name, ready=ready, url=url)
    if not url or not ready:
        return stat
    try:
        r = await client.get(f"{url.rstrip('/')}/stats", timeout=2.0)
        r.raise_for_status()
        body = r.json()
        stat.counters = body.get("counters") or {}
        stat.recent = body.get("recent") or []
    except Exception as e:  # noqa: BLE001
        stat.error = str(e)
    return stat


async def gather_stats() -> list[ConsumerStat]:
    services = kube.list_knative_services()
    services.sort(key=lambda s: s.get("metadata", {}).get("name", ""))
    async with httpx.AsyncClient() as client:
        tasks = [_fetch_one(client, s) for s in services]
        return await asyncio.gather(*tasks)


@dataclass
class FlowSnapshot:
    ts_ms: int
    per_version_count: dict[str, int]
    recent: list[dict[str, Any]]


async def flow_snapshot(limit: int = 50) -> FlowSnapshot:
    stats = await gather_stats()
    per_version: dict[str, int] = {}
    recent: list[dict[str, Any]] = []
    for s in stats:
        per_version[s.version] = s.counters.get("accepted", 0)
        for r in s.recent:
            r2 = dict(r)
            r2["_consumer"] = s.ksvc_name
            recent.append(r2)
    recent.sort(key=lambda r: r.get("ts_ms", 0), reverse=True)
    return FlowSnapshot(ts_ms=int(time.time() * 1000), per_version_count=per_version, recent=recent[:limit])
