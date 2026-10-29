# Kubernetes plan (Phase 3), the honest version

Status: plan written 2026-10-29. The manifests are drafts in `deploy/k8s/` and have **never been applied to a cluster**.
Decision record: `docs/adr/0014-kubernetes-entry-criteria.md`. Background: ADR 0006 (Compose first, k3s later), ADR 0005 (live stream in memory).

## 1. Where we really are
- The thing that matters now runs on **Docker Compose on one PC** (the pizzeria trial). That works and is tested.
- The single-server production setup (`deploy/docker-compose.prod.yml` with Caddy) is written and validated but **has never been run on a real server**
  (`docs/DEPLOY.md` says so). Kubernetes on top of an untried server setup is the wrong order: a first rehearsal with plain Compose teaches more per hour.
- Kubernetes is therefore a **learning and portfolio goal and a future operations tool**, not something a one-restaurant pilot needs. That is a legitimate
  reason (ADR 0006 says so), but it should be a choice, not a belief that the pizzeria needs it.

## 2. What Kubernetes would give us, and what it would cost
| Gives | Honest note for this app |
|-------|--------------------------|
| Restarts a crashed container by itself | Compose does that too (`restart: unless-stopped`). |
| Rolling updates (start the new version before stopping the old) | Real, but with **one backend replica** both versions serve for a few seconds, and the in-memory state is not shared (section 3). |
| Declarative config and a staging/production split in namespaces | Real and the main benefit at our size. |
| Scale to more replicas | **Blocked today** for the backend (section 3). The frontend can already run two. |
| Certificates, ingress, health-based routing | Possible with Caddy too. |
| Cost | A cluster to learn and operate; about 3 GB of RAM for k3s plus the app; every outage now has one more possible cause. |

## 3. What stops the backend from running more than one copy
Kept in the memory of one backend process, not in the database:
1. the live kitchen stream (`TicketEvents`, ADR 0005): an order created on copy A is invisible to a kitchen screen connected to copy B;
2. the assistant's pending proposals (`ProposalStore`): a card made on copy A cannot be confirmed on copy B;
3. rate limiters and throttles (`AiGate`, voice and guest gates, `RecoveryThrottle`, the audit limiter): limits would be per copy;
4. in a rolling update the old and the new copy overlap for seconds: streams reconnect by themselves, proposals expire in 10 minutes and the person
   asks again, limits reset. Acceptable, but it is why the manifests say **one replica**.
To go to two or more, in this order of effort: (a) Postgres `LISTEN/NOTIFY` for the live stream, (b) proposals in a table, (c) limiters in a table or a small
shared store, (d) sticky connections are NOT a fix for (a). Do this only when load or availability demands it.

## 4. Target shape (a single-node k3s on one server)
```
internet --> Traefik (k3s ingress, HTTPS from cert-manager) --> /api  --> backend  (1 pod)  --> db (StatefulSet, 1 pod, 10 GB volume)
                                                           \--> /     --> frontend (2 pods)
namespaces: ristomanager-staging and ristomanager-production (separate databases and Secrets), same node
```
Drafts in `deploy/k8s/` (kustomize, no Helm: it is built into `kubectl`): `base/` plus `overlays/staging` and `overlays/production`, `optional/` for cert-manager,
Traefik security headers, a backup CronJob and the Secret instructions. Pods run as non-root with a read-only filesystem, dropped capabilities and
resource limits; NetworkPolicies allow only ingress -> frontend/backend, frontend -> backend, backend -> database. Egress is not restricted yet.

## 5. Rules that must hold before it is safe to roll out
1. **Expand, then contract.** A rolling update runs the new migration while the old version still serves. Every migration must be additive for one release
   (add columns and tables; never drop or rename in the same release); old code must work with the new schema. (Today's V1 to V10 already are.)
2. **Graceful shutdown.** Add `server.shutdown: graceful` and a shutdown phase timeout to the backend (not done yet) so in-flight requests finish; the manifests add
   a `preStop` pause and a 45 s grace period.
3. **Secrets are never in git.** Created with `kubectl create secret` (`deploy/k8s/optional/secrets.example.md`).
4. **Images are pinned by exact `sha-...` tag in production**; `cloud-latest` is for staging only.
5. **A restore has been tested.** The database is one pod on one volume: the same single point of failure as Compose. The backup CronJob is drafted and optional;
   nothing counts until a restore into a scratch database has worked.

## 6. Steps, each with a way to know it worked
| # | Step | Done when |
|---|------|-----------|
| 0 | Rehearse `deploy/docker-compose.prod.yml` on a real machine (a cheap VM, or locally with a test domain) | the app is reachable over https, an update works, a restore into a scratch database works |
| 1 | Try the manifests on a local cluster (Docker Desktop's Kubernetes option, k3d or kind) with locally built images | `kubectl apply -k` gives running pods, the login works through the ingress, a kitchen screen gets a live order, `rollout undo` works. Done once on 2026-10-29, see section 8 (NetworkPolicies were enforced there) |
| 2 | Install single-node k3s on a server, cert-manager, the Secret, deploy staging | https works, a new image tag rolls out with no failed request in a script that polls `/api/v1/status` every 200 ms during the update |
| 3 | Production namespace with a pinned sha, rollback and restore rehearsed; CI deploys staging on every green push, production by a manual promote | one full release and one rollback done without help |
| 4 | Only if needed: share the in-memory state (section 3) and raise the backend to two replicas | the same script during a pod kill shows no lost orders |
| 5 | Monitoring: Micrometer Prometheus endpoint (cluster-only), a small dashboard, and an uptime check | a stopped backend is noticed in under a minute |

## 7. Things I could not verify without a cluster
Whether every manifest field is accepted by the API server (only rendering with `kubectl kustomize` is checked), the Traefik Middleware and cert-manager objects, the Postgres
init script behaviour on a volume, probe timings of the JVM plus Flyway, NetworkPolicy enforcement by the chosen network plugin, and the real memory footprint.

## 8. First local trial, 2026-10-29 (step 1 of section 6)
Cluster: Docker Desktop's built-in Kubernetes (kind, v1.36.1, one node, kindnet). Overlay: `deploy/k8s/overlays/local` (local images, no Ingress). Images built from the branch and loaded into the node.
What was measured, not assumed:
- The real API server accepted every object of the base (a stronger check than rendering).
- Pods came up: database, backend, frontend. Through `kubectl port-forward` the frontend page loaded, `/api` reached the backend, `database: UP`, signup, `/me` and a login worked, and a request without a token got 401.
- **Rolling restart of the backend while polling `/api/v1/status` every 200 ms: 268 of 268 requests were 200.** One run, one replica, no real load; it is not a claim about production.
- `rollout undo` returned to the previous revision and the pod became Ready.
- Killing the database pod: it was back in about 18 s, the 10 Gi volume stayed bound and a login worked afterwards. The API answered nothing for about 10 s meanwhile (a single database pod is a single point of failure, as written in section 5).
- **NetworkPolicies are enforced by this cluster's kindnet** (this corrects the note in step 1): a pod from another namespace and the frontend pod could not reach the backend or the database respectively, and the frontend reached the backend.
- **A real bug found:** on a cold start the backend ran Flyway before the database answered, exited, and was restarted twice. Fixed with a `wait-for-db` init container (`pg_isready`; it needs `-U` because uid 10001 has no user name in the image, found by testing). After the fix, killing the database and backend together gave a backend with 0 restarts.
Not covered: the Ingress and TLS (no Traefik or cert-manager here), the Traefik Middleware, the backup CronJob, the production overlay, graceful shutdown (`server.shutdown` is still not set), more than one backend replica, and any load.

## 9. Decisions AJ owes (none tonight)
Whether Kubernetes is a goal in itself (learning, portfolio) or only a means; which server it would run on; when step 0 happens. The plan above does not depend on the answers until step 2.
