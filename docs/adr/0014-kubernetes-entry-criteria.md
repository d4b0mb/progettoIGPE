# 0014. Kubernetes: entry criteria, and the backend stays one replica

Status: Proposed (2026-10-29). Refines ADR 0006; the detail is in `docs/KUBERNETES_PLAN.md`.

## Situation
ADR 0006 chose Docker Compose first and k3s in Phase 3. Since then the app gained state that lives in one backend process (live kitchen stream, assistant
proposals, rate limiters), and the single-server production setup has still never run on a real server. Manifests for k3s are now drafted in `deploy/k8s/`.

## Decision
1. **Keep k3s as Phase 3, but with entry criteria.** It starts only after (a) the Compose production setup has been rehearsed on a real machine (https, an update, a restore into a scratch
   database) and (b) a backup restore has worked once. Until then the pizzeria trial and any pilot run on Compose.
2. **The backend runs exactly one replica** until its in-memory state is moved to a shared place (Postgres `LISTEN/NOTIFY` for the live stream, a table for proposals, a shared limiter
   store). The frontend may run two. Rolling updates use `maxSurge: 1, maxUnavailable: 0` and accept a few seconds of overlap.
3. **Kustomize, not Helm.** It is built into `kubectl`, shows what is applied, and has no extra tool or templating language to learn for a single application.
4. **Expand, then contract** for every database migration (additive for one release), because a rolling update runs the new migration while the old version still serves.
5. **No claim of zero downtime** in docs until a script that polls the API during an update has shown it on a real cluster.

## Consequences
- Kubernetes is presented honestly as a learning and future-operations goal, not a need of the pilot.
- Moving to two backend replicas becomes a named piece of work with a test (no lost orders when a pod is killed), not a setting change.
- The drafts can be reviewed and rendered now; nothing about them is proven until step 1 of the plan has run on a cluster.

## Rejected
- *Scale the backend now by sticky sessions*: does not fix the live stream (events are produced on whichever copy handled the order).
- *Managed Kubernetes or a managed database now*: costs money and the pilot does not need it.
- *Skipping the Compose rehearsal and going straight to k3s*: adds a cluster to an untried deployment, so two unknowns at once.
