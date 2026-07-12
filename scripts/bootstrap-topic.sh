#!/usr/bin/env bash
# Ensure the `events` topic exists on the in-cluster Redpanda. Idempotent.
set -euo pipefail

NS="${NAMESPACE:-schema-demo}"
TOPIC="${TOPIC:-events}"
PARTS="${PARTITIONS:-4}"

POD=$(kubectl -n "$NS" get pod -l app.kubernetes.io/name=redpanda -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
if [[ -z "$POD" ]]; then
  echo "redpanda pod not found in $NS; skipping topic bootstrap"
  exit 0
fi

kubectl -n "$NS" exec "$POD" -- rpk -X brokers=redpanda:9092 topic create "$TOPIC" --partitions "$PARTS" --replicas 1 || true
kubectl -n "$NS" exec "$POD" -- rpk -X brokers=redpanda:9092 topic list
