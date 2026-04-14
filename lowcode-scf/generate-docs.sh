#!/usr/bin/env bash
# Regenerate the static HTML documentation for the document-enricher
# AsyncAPI contract. The rendered site lands in ./public/ and is served
# by the dashboard at /lowcode-docs/.
#
# Requires: node + npx on PATH. No other setup needed.
set -euo pipefail
cd "$(dirname "$0")"

rm -rf public
mkdir -p public

npx --yes -p @asyncapi/cli@latest asyncapi generate fromTemplate \
  asyncapi.yaml \
  @asyncapi/html-template@3 \
  -o public \
  --force-write

echo "AsyncAPI HTML generated at: $(pwd)/public/index.html"
