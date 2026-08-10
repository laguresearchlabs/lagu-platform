#!/bin/bash
# RETIRED — do not run against anything you care about.
#
# This script is kept only so that a stale copy in someone's shell history
# fails loudly instead of quietly creating wrong topics. It was already out of
# date with the code before it was retired:
#
#   * it created `platform.metadata.changed` and `platform.team.events`, neither
#     of which appears anywhere in the source — metadata-service was retired and
#     absorbed into schema-registry
#   * it was missing five topics the services actually use:
#     platform.booking.events, platform.document.events, platform.listing.events,
#     platform.schema.events, platform.verification.events
#   * every topic was created with --replication-factor 1
#
# Topics are now declared, not scripted:
#
#   PRODUCTION   server-scripts/prod/data/kafka-topics.yaml
#                Strimzi KafkaTopic CRs, RF=3, min.insync.replicas=2, reconciled
#                by the operator. auto.create.topics.enable is off, so a typo in
#                a topic name fails loudly instead of silently creating an empty
#                topic nobody produces to.
#
#   LOCAL DEV    server-scripts/services/docker-compose.yml sets
#                KAFKA_AUTO_CREATE_TOPICS_ENABLE=true, so the broker creates
#                topics on first use and nothing needs to run.
#
# The authoritative topic list is the one in kafka-topics.yaml. Add new topics
# there, and add them to the local stack by simply producing to them.

cat >&2 <<'EOF'
topics.sh is retired.

  Production: topics are Strimzi KafkaTopic CRs in
              server-scripts/prod/data/kafka-topics.yaml
  Local dev:  the broker auto-creates topics; nothing to run.

Running this would create topics at replication-factor 1 and miss five of the
topics the platform actually uses.
EOF
exit 1
