#!/bin/bash
# Creates platform Kafka topics with correct partition counts.
# Run once against a live broker: ./topics.sh [bootstrap-server]
# Defaults to localhost:9092 for local dev.

BOOTSTRAP=${1:-localhost:9092}

create() {
  local topic=$1 partitions=$2
  kafka-topics.sh --bootstrap-server "$BOOTSTRAP" \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions "$partitions" \
    --replication-factor 1
}

# Main topics — partition counts reflect relative throughput
create platform.metadata.changed  3
create platform.record.events     12   # highest throughput; keyed by orgId:recordId
create platform.workflow.events   6
create platform.team.events       3
create platform.automation.events 3

# Dead letter topics (single partition each)
for topic in platform.metadata.changed platform.record.events platform.workflow.events \
             platform.team.events platform.automation.events; do
  create "${topic}.DLT" 1
done

echo "Done. Topics:"
kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --list | grep "^platform"
