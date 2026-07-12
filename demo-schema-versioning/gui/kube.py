"""Thin wrapper over the kubernetes Python client.

Resolved once at import time. Uses `KUBECONFIG` env or `~/.kube/config`.
Falls back to in-cluster config if available.
"""

from __future__ import annotations

import logging
from functools import lru_cache
from typing import Any, cast

from kubernetes import client, config
from kubernetes.client.exceptions import ApiException
from kubernetes.client.models import V1Deployment

log = logging.getLogger("gui.kube")

NAMESPACE = "schema-demo"


@lru_cache(maxsize=1)
def _load_config() -> None:
    import os
    from pathlib import Path

    # Honor KUBECONFIG if set, even when relative — try resolving against
    # several plausible roots (cwd, gui/, demo-schema-versioning/, repo root).
    env = os.environ.get("KUBECONFIG", "")
    if env:
        candidates = [Path(env)]
        if not Path(env).is_absolute():
            here = Path(__file__).resolve()
            candidates.extend(
                [
                    Path.cwd() / env,
                    here.parent / env,
                    here.parent.parent / env,
                    here.parent.parent.parent / env,
                ]
            )
        for p in candidates:
            if p.exists():
                config.load_kube_config(config_file=str(p))
                log.info("loaded kubeconfig %s", p)
                return
        log.warning("KUBECONFIG=%s not found in any expected location", env)
    try:
        config.load_kube_config()
        log.info("loaded kubeconfig from host default")
        return
    except Exception:
        pass
    try:
        config.load_incluster_config()
        log.info("loaded in-cluster kubeconfig")
    except Exception:
        log.exception("no kubeconfig available; kube operations will fail")


def core_v1() -> client.CoreV1Api:
    _load_config()
    return client.CoreV1Api()


def apps_v1() -> client.AppsV1Api:
    _load_config()
    return client.AppsV1Api()


def custom() -> client.CustomObjectsApi:
    _load_config()
    return client.CustomObjectsApi()


KNATIVE_SERVING = ("serving.knative.dev", "v1")
KNATIVE_EVENTING = ("eventing.knative.dev", "v1")


def list_knative_services() -> list[dict[str, Any]]:
    group, version = KNATIVE_SERVING
    try:
        res = custom().list_namespaced_custom_object(
            group=group, version=version, namespace=NAMESPACE, plural="services"
        )
        return list(res.get("items", []))
    except ApiException as e:
        log.warning("list services failed: %s", e)
        return []


def list_triggers() -> list[dict[str, Any]]:
    group, version = KNATIVE_EVENTING
    try:
        res = custom().list_namespaced_custom_object(
            group=group, version=version, namespace=NAMESPACE, plural="triggers"
        )
        return list(res.get("items", []))
    except ApiException as e:
        log.warning("list triggers failed: %s", e)
        return []


def get_producer_active_versions() -> list[str]:
    try:
        dep = cast(V1Deployment, apps_v1().read_namespaced_deployment(name="producer", namespace=NAMESPACE))
    except ApiException:
        return []
    if dep.spec is None or dep.spec.template.spec is None:
        return []
    for c in dep.spec.template.spec.containers:
        if c.name == "producer":
            for e in c.env or []:
                if e.name == "ACTIVE_VERSIONS":
                    return [v for v in (e.value or "").split(",") if v]
    return []


def set_producer_active_versions(versions: list[str]) -> None:
    body = {
        "spec": {
            "template": {
                "spec": {
                    "containers": [
                        {
                            "name": "producer",
                            "env": [{"name": "ACTIVE_VERSIONS", "value": ",".join(versions)}],
                        }
                    ]
                }
            }
        }
    }
    apps_v1().patch_namespaced_deployment(name="producer", namespace=NAMESPACE, body=body)


def service_ready(svc: dict[str, Any]) -> bool:
    for c in (svc.get("status") or {}).get("conditions", []) or []:
        if c.get("type") == "Ready" and c.get("status") == "True":
            return True
    return False


def service_env(svc: dict[str, Any]) -> dict[str, str]:
    try:
        containers = svc["spec"]["template"]["spec"]["containers"]
    except KeyError:
        return {}
    env: dict[str, str] = {}
    for c in containers:
        for e in c.get("env", []) or []:
            if "name" in e and "value" in e:
                env[e["name"]] = e["value"]
    return env


def service_url(svc: dict[str, Any]) -> str | None:
    return (svc.get("status") or {}).get("url")
