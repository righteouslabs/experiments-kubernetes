# -*- mode: Python -*-
# Tiltfile for the schema-versioning demo.
#
# Build flow:
#   - producer + consumer images are built with `docker build` on the host,
#     then side-loaded into MicroShift's CRI-O store with
#       docker save IMG | docker exec -i microshift podman load
#   - We use a `local_resource` per image (not `docker_build`/`custom_build`)
#     so Tilt does not try to rewrite manifest image refs or push to a
#     registry — both incompatible with MicroShift's containerised CRI-O.
#   - K8s manifests reference the images at a fixed `:dev` tag with
#     `imagePullPolicy: Never`, so kubelet uses the loaded copy.
#
# Kubeconfig: typically
#   export KUBECONFIG=$(pwd)/microshift-docker-compose/kubeconfig

allow_k8s_contexts([
    'microshift',
    'microshift-microshift-dc',
    'microshift/microshift',
    'default',
    'admin@microshift',
])

DEMO = 'demo-schema-versioning'
MICROSHIFT_CTR = 'microshift'

# -------- Knative + namespace bootstrap --------
local_resource(
    'install-knative',
    cmd='./scripts/install-knative.sh',
    deps=['scripts/install-knative.sh'],
    labels=['bootstrap'],
)


def build_and_load(name, dockerfile, ctx, deps_paths):
    """Build with docker, save, and load into microshift via podman load."""
    cmd = (
        'docker build -t dev.local/demo-' + name + ':dev -f ' + dockerfile + ' ' + ctx +
        ' && ( docker inspect ' + MICROSHIFT_CTR + ' >/dev/null 2>&1' +
        '      && docker save dev.local/demo-' + name + ':dev' +
        '           | docker exec -i ' + MICROSHIFT_CTR + ' podman load )' +
        ' || echo "[loader] microshift container not present — skipping load"'
    )
    local_resource(
        'image-' + name,
        cmd=cmd,
        deps=deps_paths,
        labels=['images'],
        resource_deps=['install-knative'],
    )


build_and_load(
    name='consumer',
    dockerfile=DEMO + '/consumer/Dockerfile',
    ctx=DEMO,
    deps_paths=[
        DEMO + '/consumer',
        DEMO + '/schemas',
    ],
)

build_and_load(
    name='producer',
    dockerfile=DEMO + '/producer/Dockerfile',
    ctx=DEMO,
    deps_paths=[
        DEMO + '/producer',
        DEMO + '/schemas',
    ],
)

# -------- K8s manifests --------
k8s_yaml([
    DEMO + '/k8s/namespace/namespace.yaml',
])
k8s_yaml([
    DEMO + '/k8s/redpanda/deployment.yaml',
    DEMO + '/k8s/redpanda/service.yaml',
    DEMO + '/k8s/redpanda/topic-create-job.yaml',
])
k8s_yaml([
    DEMO + '/k8s/broker/config.yaml',
    DEMO + '/k8s/broker/broker.yaml',
])
k8s_yaml([
    DEMO + '/k8s/sources/kafka-source.yaml',
])

# Teach Tilt about Knative Services so they show up as workloads.
k8s_kind(
    'Service',
    api_version='serving.knative.dev/v1',
    image_json_path='{.spec.template.spec.containers[*].image}',
)

# Pick up every consumer-<v>.yaml + trigger-<v>.yaml in the tree; this lets
# the GUI's "Add version" button scaffold new YAML and have Tilt apply it.
consumer_services = []
for svc_yaml in listdir(DEMO + '/k8s/services'):
    if svc_yaml.endswith('.yaml'):
        k8s_yaml(svc_yaml)
        consumer_services.append(svc_yaml.rsplit('/', 1)[-1].removesuffix('.yaml'))
for trig_yaml in listdir(DEMO + '/k8s/triggers'):
    if trig_yaml.endswith('.yaml'):
        k8s_yaml(trig_yaml)

k8s_yaml(DEMO + '/k8s/producer/deployment.yaml')

# -------- Resource grouping for the Tilt UI --------
k8s_resource('redpanda', labels=['kafka'])
k8s_resource(
    'producer',
    labels=['workload'],
    resource_deps=['redpanda', 'image-producer'],
)
for name in consumer_services:
    k8s_resource(
        workload=name,
        labels=['workload'],
        resource_deps=['redpanda', 'image-consumer'],
    )

# -------- GUI: host-side, port 8080 --------
local_resource(
    'gui',
    serve_cmd=(
        'cd ' + DEMO + '/gui' +
        ' && uv venv .venv --python 3.12 --quiet' +
        ' && uv pip compile pyproject.toml -q -o .requirements.txt' +
        ' && uv pip install -q -r .requirements.txt' +
        ' && exec .venv/bin/python main.py'
    ),
    serve_env={
        'PYTHONPATH': DEMO,
        # Absolute path — gui's `cd demo-schema-versioning/gui` makes any
        # relative kubeconfig path miss.
        'KUBECONFIG': str(config.main_path).rsplit('/', 1)[0] + '/microshift-docker-compose/kubeconfig',
    },
    deps=[
        DEMO + '/gui',
        DEMO + '/schemas',
    ],
    readiness_probe=probe(
        http_get=http_get_action(port=8080, path='/healthz'),
        period_secs=2,
        initial_delay_secs=3,
    ),
    links=[link('http://localhost:8080', 'GUI')],
    labels=['gui'],
)
