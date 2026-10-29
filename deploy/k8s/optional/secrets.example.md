# Creating the Secret `ristomanager-secrets`

The repository contains **no** passwords. You create the Secret by hand, once per namespace, directly
in the cluster. Never write a value into a file that goes into git.

It must have exactly these three keys:

| Key | Used by | What it is |
|-----|---------|------------|
| `POSTGRES_PASSWORD` | database, backend (Flyway), backup job | password of the database owner `ristomanager` |
| `APP_DB_PASSWORD` | database init script, backend | password of the restricted role `ristomanager_app` |
| `JWT_SECRET` | backend | signing key for logins (at least 32 characters) |

Use three different values. Generate each with `openssl rand -base64 32`.

## Staging

```sh
kubectl create namespace ristomanager-staging

kubectl -n ristomanager-staging create secret generic ristomanager-secrets \
  --from-literal=POSTGRES_PASSWORD="$(openssl rand -base64 32)" \
  --from-literal=APP_DB_PASSWORD="$(openssl rand -base64 32)" \
  --from-literal=JWT_SECRET="$(openssl rand -base64 32)"
```

## Production

```sh
kubectl create namespace ristomanager-production

kubectl -n ristomanager-production create secret generic ristomanager-secrets \
  --from-literal=POSTGRES_PASSWORD="$(openssl rand -base64 32)" \
  --from-literal=APP_DB_PASSWORD="$(openssl rand -base64 32)" \
  --from-literal=JWT_SECRET="$(openssl rand -base64 32)"
```

Staging and production must have different values.

## Check that it exists (this does not print the values)

```sh
kubectl -n ristomanager-staging describe secret ristomanager-secrets
```

You should see the three keys with their sizes in bytes.

## Things to know

- The generated values are shown nowhere. If you need them later, read them back from the cluster
  (`kubectl -n <namespace> get secret ristomanager-secrets -o jsonpath='{.data.JWT_SECRET}' | base64 -d`)
  and store them in a password manager. Anyone who can read Secrets in the namespace can read them.
- **The two database passwords are only used when the database is created.** The init script sets
  the password of `ristomanager_app` once, on an empty volume. Changing `APP_DB_PASSWORD` or
  `POSTGRES_PASSWORD` in the Secret afterwards does **not** change them inside PostgreSQL: the
  backend would then fail to log in. To rotate them, change the password in PostgreSQL
  (`ALTER ROLE ...`) and update the Secret in the same step, then restart the backend.
- Changing `JWT_SECRET` logs everybody out.
- Pods read the Secret at start. After changing it: `kubectl -n <namespace> rollout restart deployment/backend`.
- A Secret in Kubernetes is only base64-encoded, not encrypted, unless the cluster encrypts it at rest.
  Better secret management (sealed or external secrets) is on the "What is missing" list in the README.
