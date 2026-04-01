#!/usr/bin/env bash
#
# Queries MongoDB for processed documents to verify the pipeline is working.
#
set -euo pipefail

echo "=== Documents stored in MongoDB ==="
echo ""

# Count by schema version
echo "Counts by schema version:"
docker exec -i $(docker compose ps -q mongodb 2>/dev/null || echo "experiments-kubernetes-mongodb-1") \
    mongosh --quiet documentsdb --eval '
        db.documents.aggregate([
            { $group: { _id: "$value.schemaVersion", count: { $sum: 1 } } },
            { $sort: { _id: 1 } }
        ]).forEach(r => print("  v" + r._id + ": " + r.count + " documents"))
    ' 2>/dev/null || echo "  (MongoDB not reachable — is the stack running?)"

echo ""
echo "Latest 5 documents:"
docker exec -i $(docker compose ps -q mongodb 2>/dev/null || echo "experiments-kubernetes-mongodb-1") \
    mongosh --quiet documentsdb --eval '
        db.documents.find({}, { _id: 0, "value.id": 1, "value.schemaVersion": 1, "value.title": 1, "value.processedBy": 1 })
            .sort({ "value.processedAt": -1 })
            .limit(5)
            .forEach(d => printjson(d.value ? { id: d.value.id, v: d.value.schemaVersion, title: d.value.title, processedBy: d.value.processedBy } : d))
    ' 2>/dev/null || echo "  (MongoDB not reachable — is the stack running?)"
