# RistoManager on Kubernetes

> **Status: written, not yet run on a cluster.** Nothing in this folder has been applied to a real
> or local cluster. Treat the first deployment as a rehearsal and expect to fix small things.
> This is the Phase 3 step of `docs/adr/0006-deployment-path.md`. The single-server setup
> (`deploy/docker-compose.prod.yml`, see `docs/DEPLOY.md`) remains the tested way to run it.

This folder describes the same system as `deploy/docker-compose.prod.yml` (database, backend, web app,
and a front door with HTTPS), in the format Kubernetes understands. If you know Docker but not
Kubernetes, the short version is below.

## Kubernetes in five minutes (compared with Docker Compose)

| Compose | Kubernetes here | What it means |
|---------|-----------------|---------------|
| a service | a **Deployment** (or **StatefulSet** for the database) | "keep N copies of this container running, and replace them gradually when the image changes" |
| a container | a **Pod** | the running copy; the Deployment creates and replaces pods |
| service name as host name (`backend`) | a **Service** | a stable name and port in front of the pods |
| `ports:` + Caddy | an **Ingress** | the rule "this domain, this path goes to that Service" |
| `.env` | a **Secret** and a **ConfigMap** | secret settings and plain settings |
| a volume | a **PersistentVolumeClaim** | "I need 10 GB of disk that survives restarts" |
| (nothing) | **NetworkPolicy** | firewall rules between pods |
| (nothing) | **namespace** | a separate room: staging and production live in different ones |

## What is in this folder

```
deploy/k8s/
  README.md                  this file
  check-init-script.sh       fails if the two copies of the database init script differ
  base/                      everything, with placeholders (not applied directly)
    kustomization.yaml         the list of files below + the init script ConfigMap
    config.yaml                ConfigMap: the non-secret settings of the backend
    db-statefulset.yaml        PostgreSQL 17 with a 10 Gi disk
    db-service.yaml            the name "db" for the database
    db-init/01-app-role.sh     COPY of infra/postgres/01-app-role.sh (see the warning below)
    backend-deployment.yaml    the API: exactly ONE replica (read why below)
    backend-service.yaml       the name "backend" (the web app is built to call exactly this)
    frontend-deployment.yaml   the web app: 2 replicas, plus a PodDisruptionBudget
    frontend-service.yaml      the name "frontend"
    ingress.yaml               domain + HTTPS: /api goes to backend, everything else to frontend
    networkpolicies.yaml       who may talk to whom
  overlays/
    staging/                 namespace ristomanager-staging, images "cloud-latest", 1 web pod
    production/              namespace ristomanager-production, images pinned to a sha, bigger sizes
  optional/                  NOT used by the overlays; you apply these on purpose
    cert-manager-clusterissuer.yaml   gets the HTTPS certificate from Let's Encrypt
    traefik-middleware.yaml           the security headers of deploy/Caddyfile
    backup-cronjob.yaml               nightly pg_dump into its own disk (test a restore first)
    secrets.example.md                how to create the Secret (no values in git)
```

**kustomize** is built into `kubectl`. The `base` is the common description; an **overlay** says
"take the base, but with this namespace, these image tags, this domain, these sizes". It is the same
idea as a Compose override file. No Helm and no templating.

> **Warning: two copies of one file.** kustomize cannot read files outside its own folder, so
> `base/db-init/01-app-role.sh` is a copy of `infra/postgres/01-app-role.sh`. If you change one, change
> the other, then run `sh deploy/k8s/check-init-script.sh`. It prints OK or fails.

## Render without a cluster

Rendering only prints the final YAML. It needs no cluster and changes nothing:

```sh
kubectl kustomize deploy/k8s/overlays/staging
kubectl kustomize deploy/k8s/overlays/production
sh deploy/k8s/check-init-script.sh
```

## Try it on your own computer

*Not yet run on a cluster: the steps below are what should work, not what has been seen working.*

You need a small cluster on your PC. Pick one:

- **Docker Desktop**: Settings, Kubernetes, "Enable Kubernetes". Easiest if you already use Docker Desktop.
- **k3d**: runs k3s (the same Kubernetes as the planned server) inside Docker: `k3d cluster create ristomanager`.
- **kind**: Kubernetes in Docker, aimed at testing: `kind create cluster`.

Check that `kubectl get nodes` shows a node, then:

```sh
kubectl create namespace ristomanager-staging
# create the Secret: see deploy/k8s/optional/secrets.example.md
kubectl apply -k deploy/k8s/overlays/staging
kubectl -n ristomanager-staging get pods --watch
```

Wait until all pods are `Running` and ready (the first start pulls the images and runs the database
migrations; give it a couple of minutes). A local cluster usually has no Traefik, no cert-manager and no
domain, so the Ingress does nothing there. Reach the web app with a tunnel instead:

```sh
kubectl -n ristomanager-staging port-forward service/frontend 3000:3000
# then open http://localhost:3000   (it forwards /api to the backend by itself)
```

The login cookie is marked Secure (`COOKIE_SECURE=true`); browsers may refuse to keep it over plain
`http`. If login does not stick, that is the reason, not a bug in the cluster: a real HTTPS domain is the
proper test. Also note that Docker Desktop and kind do not enforce NetworkPolicies, so those are only
really tested on k3s.

## Create the namespace and the Secret

The repository holds no passwords. Create them in the cluster (full commands in
`deploy/k8s/optional/secrets.example.md`):

```sh
kubectl create namespace ristomanager-production
kubectl -n ristomanager-production create secret generic ristomanager-secrets \
  --from-literal=POSTGRES_PASSWORD="$(openssl rand -base64 32)" \
  --from-literal=APP_DB_PASSWORD="$(openssl rand -base64 32)" \
  --from-literal=JWT_SECRET="$(openssl rand -base64 32)"
```

### Private images

If the `ghcr.io` packages are private, create a pull secret named `ghcr-pull` in the same namespace
(use a token that can read packages, never your password):

```sh
kubectl -n ristomanager-production create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io --docker-username=<github-user> --docker-password=<token>
```

then remove the `#` in front of `- path: image-pull-secret-patch.yaml` in the overlay's
`kustomization.yaml`.

## On a real server (k3s)

1. Install k3s on the server (it brings Traefik, the front door, and a disk provisioner called
   `local-path`).
2. Point your domain at the server (an `A` record) and open ports 80 and 443.
3. Install **cert-manager** (follow its official installation instructions and pick the current release),
   then edit the e-mail and apply `deploy/k8s/optional/cert-manager-clusterissuer.yaml`.
4. In the overlay, replace the placeholder host (`staging.example.com` or `ristomanager.example.com`)
   in `ingress-patch.yaml` with your real domain.
5. For production: replace **both** `sha-REPLACE_ME` tags in `overlays/production/kustomization.yaml`
   with an exact build (`sha-xxxxxxx`) that CI published. The placeholder does not exist, so applying
   without replacing it fails with an image pull error (on purpose, instead of running something unknown).
6. Create the namespace and the Secret (above), then apply:

```sh
kubectl apply -k deploy/k8s/overlays/production
kubectl -n ristomanager-production get pods
kubectl -n ristomanager-production get certificate     # becomes READY when HTTPS works
```

7. Open `https://your-domain/signup` and create the real restaurant.

Optional security headers (as in `deploy/Caddyfile`): apply `deploy/k8s/optional/traefik-middleware.yaml`
in the namespace and add the annotation described at the top of that file to the Ingress.

## Updating

An update is: change the image tag, apply, watch.

```sh
# 1. production: edit both "newTag: sha-..." lines in deploy/k8s/overlays/production/kustomization.yaml
# 2. look at what will change
kubectl kustomize deploy/k8s/overlays/production
# 3. apply
kubectl apply -k deploy/k8s/overlays/production
# 4. watch the rollout
kubectl -n ristomanager-production rollout status deployment/backend
kubectl -n ristomanager-production rollout status deployment/frontend
```

How a rollout goes: Kubernetes starts a **new** pod, waits until its health check passes (the backend may
need up to 150 seconds for the JVM and the database migrations), and only then stops the old one. So
the web app never has zero pods. The kitchen screens lose their live stream for a moment when the old
backend stops, and reconnect by themselves.

Staging with `cloud-latest`: the tag does not change when a new image is published, so apply alone does
nothing. Force a fresh pull with `kubectl -n ristomanager-staging rollout restart deployment/backend deployment/frontend`.
The staging overlay sets `imagePullPolicy: Always` so the new pods download the image again.

If you change `config.yaml` or the Secret, the same applies: restart the backend to pick it up.

## Rolling back

```sh
kubectl -n ristomanager-production rollout history deployment/backend
kubectl -n ristomanager-production rollout undo deployment/backend
kubectl -n ristomanager-production rollout status deployment/backend
```

Then also put the old tag back in the overlay file, otherwise the next `apply` brings the new version
again. **A rollback does not undo database migrations.** That is why the next rule exists.

## The migration rule: expand, then contract

While a new backend version starts, its migration runs **while the old version is still serving**, and
after a rollback the old code runs against the **new** schema. So every migration must be *additive*, and
old code must keep working with the new schema for one release:

1. **Expand** (release N): add the new column, table or index. Do not remove or rename anything. The
   old code ignores what it does not know. New columns must be nullable or have a default.
2. **Switch** (release N, code): the new code starts using the new column.
3. **Contract** (release N+1 or later, once N is safe and will not be rolled back): a later migration
   removes the old column or table that nothing uses anymore.

Never in one release: rename a column, drop a column the old code reads, add a `NOT NULL` column
without a default, or change a type in a way the old code cannot read. If you need one of these, split it
into the steps above. Take a database backup before a release that contains a migration.

## Why the backend has exactly one replica

Some state lives only in the memory of the backend process: the live kitchen events, the assistant's
proposals waiting for confirmation, and the rate limits (`docs/adr/0005-server-sent-events-for-kitchen.md`).
With two backend pods an order sent through one would not reach a kitchen screen connected to the
other, and limits would be counted separately. The failure is silent, so the manifest says one replica
and nothing should scale it.

Before it can be two, that state has to move to something shared (PostgreSQL LISTEN/NOTIFY or Redis for
events; shared storage for proposals and limits). The plan is in
[`docs/KUBERNETES_PLAN.md`](../../docs/KUBERNETES_PLAN.md).

The cost: there is no redundancy. If the backend pod crashes or its node fails, the API is down until
a new pod is ready. The rolling update settings keep a planned update down to a reconnect, but an
unplanned failure is an outage.

## What is missing

Be honest about these before real customers use it:

- **Backups are not tested.** `optional/backup-cronjob.yaml` is written but never run, it is not in the
  overlays, and the files live on the same server unless you copy them off. Do a restore test.
- **No monitoring or alerts.** Nothing tells you when a pod crashes, the disk fills, or the certificate fails to renew.
- **Secret management is manual.** Secrets are created by hand with `kubectl` and are not encrypted at rest
  unless the cluster is set up for it. No rotation procedure beyond the notes in `secrets.example.md`.
- **Egress is not restricted.** NetworkPolicies only limit incoming traffic; pods can still call out freely.
- **The database is one pod on one disk.** No replica, no automatic failover; if the server dies, the data is on that server.
- **No HTTP to HTTPS redirect is configured** for plain port 80 (apart from what cert-manager needs).
- **No first rehearsal on a real server.** None of this has been applied to a cluster yet.
- **No log collection.** `kubectl logs` only, and logs vanish with the pod.
