# Kafka Operations Guide

Apache Kafka is a distributed event streaming platform used for high-throughput,
fault-tolerant messaging between services.

## Topics and Partitions

A topic is split into partitions; each partition is an ordered, append-only log.
Choose the partition count from your target throughput: measure the per-partition
throughput of a single producer (call it `p`) and a single consumer (`c`), then
use at least `max(target/p, target/c)` partitions. Over-partitioning wastes file
handles and slows leader elections, so avoid going beyond a few thousand
partitions per broker.

For production topics, set a replication factor of 3 with
`min.insync.replicas=2`. This tolerates one broker failure without losing
availability and two without losing acknowledged data when producers use
`acks=all`.

## Consumer Groups

Consumers in the same group divide partitions among themselves. When a consumer
joins or leaves, the group coordinator triggers a rebalance: partitions are
revoked and reassigned. Frequent rebalancing is usually caused by
`max.poll.interval.ms` being exceeded (slow processing) or session timeouts from
long GC pauses. Cooperative sticky assignment (`CooperativeStickyAssignor`)
reduces stop-the-world rebalances by moving only the partitions that must move.

## Retention and Compaction

`retention.ms` controls how long records are kept regardless of consumption.
Log compaction (`cleanup.policy=compact`) instead keeps the latest record per
key, which suits changelog-style topics backing key-value state.

## Integrating with Caches

Kafka works well as an invalidation backbone for caches: publish a change event
on write, and let each service consume the topic and evict or update its local
or Redis-backed cache entry. This gives eventual consistency without cache
services polling the database.
