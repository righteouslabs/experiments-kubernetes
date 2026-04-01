#!/usr/bin/env bash
#
# Registers the JSON schemas with the Confluent Schema Registry.
# Run this after the stack is up and schema-registry is healthy.
#
set -euo pipefail
cd "$(dirname "$0")/.."

SCHEMA_REGISTRY_URL="${SCHEMA_REGISTRY_URL:-http://localhost:8081}"

echo "Registering document schemas with Schema Registry at ${SCHEMA_REGISTRY_URL}..."
echo ""

for version in 1 2 3; do
    schema_file="schemas/document-v${version}.json"
    subject="documents-v${version}"

    if [ ! -f "$schema_file" ]; then
        echo "  SKIP: $schema_file not found"
        continue
    fi

    # Escape the schema JSON for embedding in the registration payload
    schema_json=$(jq -c . "$schema_file" | jq -Rs .)

    payload=$(cat <<EOF
{
  "schemaType": "JSON",
  "schema": ${schema_json}
}
EOF
)

    response=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
        -H "Content-Type: application/vnd.schemaregistry.v1+json" \
        -d "$payload" \
        "${SCHEMA_REGISTRY_URL}/subjects/${subject}/versions")

    if [ "$response" = "200" ]; then
        echo "  OK: Registered ${subject} from ${schema_file}"
    else
        echo "  WARN: Got HTTP ${response} registering ${subject}"
    fi
done

echo ""
echo "Registered subjects:"
curl -s "${SCHEMA_REGISTRY_URL}/subjects" | jq .
