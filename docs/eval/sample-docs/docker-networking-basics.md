# Docker Networking Basics

Docker gives each container its own network namespace and connects containers
through virtual networks.

## Bridge vs Host Networking

The default `bridge` network attaches containers to a private virtual switch on
the host; containers get their own IP and outbound traffic is NATed. `host`
networking removes the isolation entirely — the container shares the host's
network stack, so there is no NAT and no port mapping, which lowers latency but
means port conflicts with the host are possible. Use `host` only for
performance-critical workloads on Linux; on Docker Desktop (Windows/macOS) host
networking does not expose ports to the real host network.

## User-Defined Bridges and DNS

Containers on the same user-defined bridge network resolve each other by
container name through Docker's embedded DNS server at 127.0.0.11. The default
bridge does NOT provide name resolution (only legacy `--link`), which is why
compose projects always create their own network: `docker compose` puts all
services on one user-defined bridge so `postgres` or `ollama` are reachable by
service name.

## Publishing Ports

To reach a container from the host or the outside world, publish a port with
`-p HOST_PORT:CONTAINER_PORT` (or `ports:` in compose). The mapping is enforced
by a userland proxy plus iptables DNAT rules. `-p 127.0.0.1:5432:5432` binds the
mapping to localhost only, which is the safe default for databases in
development.

## Inspecting Networks

`docker network ls` lists networks; `docker network inspect <name>` shows
connected containers and their IPs. `docker exec <c> getent hosts <name>`
verifies DNS resolution from inside a container.
