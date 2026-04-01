#!/usr/bin/env bash
#
# Deploys the demo to a MicroShift cluster (via the microshift-docker-compose submodule).
#
# Prerequisites:
#   1. MicroShift running via: cd microshift-docker-compose && docker compose up -d
#   2. kubectl configured to talk to MicroShift
#   3. Dapr installed on the cluster:  dapr init -k
#   4. Knative Serving installed on the cluster
#
set -euo pipefail
cd "$(dirname "$0")/.."

NAMESPACE="versioned-demo"

echo "========================================="
echo " Deploying to MicroShift"
echo "========================================="

# Build images (MicroShift uses local images with imagePullPolicy: IfNotPresent)
echo ""
echo "[1/4] Building container images..."
docker build -t document-producer:latest ./producer
docker build -t document-consumer:latest ./consumer-service

# Create namespace
echo ""
echo "[2/4] Creating namespace..."
kubectl apply -f k8s/namespace.yaml

# Deploy infrastructure
echo ""
echo "[3/4] Deploying infrastructure (Kafka, MongoDB, Dapr components)..."
kubectl apply -f k8s/infrastructure/
kubectl apply -f k8s/dapr/

echo "Waiting for Kafka to be ready..."
kubectl -n "$NAMESPACE" wait --for=condition=available deployment/kafka --timeout=120s
kubectl -n "$NAMESPACE" wait --for=condition=available deployment/mongodb --timeout=60s

# Deploy services
echo ""
echo "[4/4] Deploying producer and Knative consumer services..."
kubectl apply -f k8s/services/
kubectl apply -f k8s/knative/

echo ""
echo "Done! Check status with:"
echo "  kubectl -n $NAMESPACE get pods"
echo "  kubectl -n $NAMESPACE get ksvc"
echo "  kubectl -n $NAMESPACE logs -l app=producer -f"
