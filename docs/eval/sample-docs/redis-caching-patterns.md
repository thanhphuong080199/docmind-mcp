# Redis Caching Patterns

Redis is an in-memory data store commonly used as a cache in front of a slower
system of record.

## Cache-Aside (Lazy Loading)

The application owns the cache logic: on read, try `GET key`; on a miss, load
from the database, then `SET key value EX ttl`. On write, update the database
first and then delete the cache key (delete, don't set, to avoid racing a
concurrent reader writing a stale value). Cache-aside is the default pattern
because the cache failing only costs latency, not correctness.

## Write-Through and Write-Behind

Write-through updates the cache synchronously on every write, keeping hit rates
high at the cost of write latency. Write-behind batches writes to the database
asynchronously; it improves write throughput but risks data loss if Redis dies
before the flush.

## Eviction Policies

Set `maxmemory` and pick a policy. For an LRU cache use
`maxmemory-policy allkeys-lru`, which evicts the least recently used keys across
the whole keyspace. Use `volatile-lru` when only keys with a TTL should be
evictable. `allkeys-lfu` (least frequently used) is better when a small hot set
dominates and occasional scans would otherwise flush the LRU.

## TTL Strategy

Always set a TTL as a safety net even with explicit invalidation; add jitter
(e.g. ±10%) to avoid thundering herds when many keys expire together. For
event-driven invalidation, pair Redis with a message log such as Kafka: services
consume change events and delete the affected keys instead of waiting for TTLs.
