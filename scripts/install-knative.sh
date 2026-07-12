#!/usr/bin/env bash
# Install Knative Serving, Eventing, and the Kafka Broker controller.
# Idempotent: every kubectl call uses `apply`, every wait tolerates "already there".

set -euo pipefail

# Knative release tags — pick versions that exist for every component. As of
# 2026-05 these line up cleanly at the 1.21.x family.
KNATIVE_SERVING_VERSION="${KNATIVE_SERVING_VERSION:-v1.21.2}"
KNATIVE_EVENTING_VERSION="${KNATIVE_EVENTING_VERSION:-v1.21.2}"
KNATIVE_KAFKA_VERSION="${KNATIVE_KAFKA_VERSION:-v1.21.3}"
KOURIER_VERSION="${KOURIER_VERSION:-v1.21.0}"

log() { printf '\033[1;36m[install-knative]\033[0m %s\n' "$*"; }

wait_crd() {
  local crd=$1
  log "waiting for CRD $crd ..."
  for _ in $(seq 1 60); do
    if kubectl get crd "$crd" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  log "WARN: CRD $crd not present after 120s"
  return 1
}

apply_url() {
  local url=$1
  log "kubectl apply -f $url"
  # MicroShift's API server occasionally drops mid-apply on large manifests;
  # retry up to 5 times with a brief backoff so a transient EOF doesn't
  # tank the whole install.
  local attempt
  for attempt in 1 2 3 4 5; do
    if kubectl apply -f "$url"; then
      return 0
    fi
    log "apply attempt $attempt for $url failed; retrying in 5s..."
    sleep 5
  done
  log "ERROR: apply of $url failed after 5 attempts"
  return 1
}

main() {
  log "Serving CRDs"
  apply_url "https://github.com/knative/serving/releases/download/knative-${KNATIVE_SERVING_VERSION}/serving-crds.yaml"
  log "Serving core"
  apply_url "https://github.com/knative/serving/releases/download/knative-${KNATIVE_SERVING_VERSION}/serving-core.yaml"

  log "Kourier (HTTP ingress)"
  apply_url "https://github.com/knative-extensions/net-kourier/releases/download/knative-${KOURIER_VERSION}/kourier.yaml"
  kubectl patch configmap/config-network \
    --namespace knative-serving \
    --type merge \
    --patch '{"data":{"ingress-class":"kourier.ingress.networking.knative.dev"}}'

  log "Eventing CRDs"
  apply_url "https://github.com/knative/eventing/releases/download/knative-${KNATIVE_EVENTING_VERSION}/eventing-crds.yaml"
  log "Eventing core"
  apply_url "https://github.com/knative/eventing/releases/download/knative-${KNATIVE_EVENTING_VERSION}/eventing-core.yaml"

  log "Kafka controller"
  apply_url "https://github.com/knative-extensions/eventing-kafka-broker/releases/download/knative-${KNATIVE_KAFKA_VERSION}/eventing-kafka-controller.yaml"
  log "Kafka source"
  apply_url "https://github.com/knative-extensions/eventing-kafka-broker/releases/download/knative-${KNATIVE_KAFKA_VERSION}/eventing-kafka-source.yaml"
  log "Kafka broker dataplane"
  apply_url "https://github.com/knative-extensions/eventing-kafka-broker/releases/download/knative-${KNATIVE_KAFKA_VERSION}/eventing-kafka-broker.yaml"

  log "Setting default broker class to Kafka"
  kubectl patch configmap/config-br-defaults \
    --namespace knative-eventing \
    --type merge \
    --patch '{"data":{"default-br-config":"clusterDefault:\n  brokerClass: Kafka\n  apiVersion: v1\n  kind: ConfigMap\n  name: kafka-broker-config\n  namespace: schema-demo\n"}}' || true

  log "Telling Knative Serving to skip digest resolution for localhost/* images"
  # Otherwise revisions get stuck in ContainerMissing because Knative tries to
  # resolve `localhost/demo-consumer:dev` against docker.io and DNS-fails.
  kubectl patch configmap/config-deployment \
    --namespace knative-serving \
    --type merge \
    --patch '{"data":{"registries-skipping-tag-resolving":"localhost,kind.local,ko.local,dev.local"}}' || true
  # The controller does not always reload this map dynamically; bounce it.
  log "Bouncing knative-serving controller to pick up the new registries config"
  kubectl -n knative-serving rollout restart deployment/controller || true
  kubectl -n knative-serving rollout status deployment/controller --timeout=120s || true

  # MicroShift inherits OpenShift's SCC. The kafka-broker dataplane manifests
  # use a runAsUser outside the cluster's allowed range, so the dataplane
  # pods get blocked with "unable to validate against any security context
  # constraint". Granting `anyuid` to the relevant SAs unblocks them.
  log "Granting OpenShift SCC privileged to dev SAs"
  # anyuid is too restrictive — pod templates set seccomp annotations that
  # only privileged allows. Privileged SCC is fine for a dev cluster.
  # SA list: ns:sa
  for nssa in \
      "knative-eventing:knative-kafka-broker-data-plane" \
      "knative-eventing:knative-kafka-source-data-plane" \
      "kourier-system:default"
  do
    ns="${nssa%%:*}"; sa="${nssa##*:}"
    kubectl delete clusterrolebinding "scc-anyuid-${ns}-${sa}" --ignore-not-found=true || true
    kubectl create clusterrolebinding "scc-privileged-${ns}-${sa}" \
      --clusterrole=system:openshift:scc:privileged \
      --serviceaccount="${ns}:${sa}" \
      --dry-run=client -o yaml | kubectl apply -f - || true
  done
  # Bounce the dataplane deployments so they retry pod creation now that the
  # binding is in place. Do NOT delete the dispatcher StatefulSets — they are
  # created by the eventing-kafka-broker.yaml apply above and the kafka
  # control plane does not autorecreate them.
  kubectl -n knative-eventing rollout restart deployment/kafka-broker-receiver || true
  kubectl -n kourier-system rollout restart deployment/3scale-kourier-gateway || true
  # Restart any existing dispatcher pods so they pick up the SCC binding.
  kubectl -n knative-eventing delete pod -l app=kafka-broker-dispatcher --ignore-not-found=true || true
  kubectl -n knative-eventing delete pod -l app=kafka-source-dispatcher --ignore-not-found=true || true

  wait_crd brokers.eventing.knative.dev
  wait_crd triggers.eventing.knative.dev
  wait_crd kafkasources.sources.knative.dev
  log "done."
}

main "$@"
