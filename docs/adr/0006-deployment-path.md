# 0006 - Docker Compose first, k3s in Phase 3

**Status:** Accepted

## Context
Kubernetes is the right tool for zero-downtime updates and growth to more
servers, but it adds significant operational weight.

## Decision
Every part ships as a Docker image. Phases 0 to 2 run with Docker Compose, locally
and on a single server. Phase 3 moves to k3s (lightweight Kubernetes) on one VPS
with separate staging and production namespaces, once there is something real to
deploy and a pilot restaurant to protect.

## Consequences
- Pros: learn Kubernetes with a real workload instead of a toy; early phases stay
  simple.
- Cons: a migration step from Compose to Kubernetes manifests later; staging
  parity with production is weaker until Phase 3.
