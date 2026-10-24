# 0008 - How row-level security is wired

**Status:** Accepted (implements the second lock of [0003](0003-multi-tenancy.md))

## Context
Row-level security (RLS) only protects against roles that are subject to it.
PostgreSQL superusers and table owners skip it, and the default user of the Docker
image is a superuser. Policies that never apply would give a false sense of safety.

## Decision
- **Two database roles.** Flyway connects as the owner and creates tables and
  policies. The API connects as `ristomanager_app`: not superuser, not owner,
  `NOBYPASSRLS`. It is created by `infra/postgres/01-app-role.sh`, which both
  Docker Compose and the tests run.
- **The restaurant travels with the connection.** After the login token is verified,
  a filter puts the restaurant id in a thread-local. A wrapper around the
  connection pool copies it into the session variable `app.restaurant_id` on
  *every* checkout (empty when unset), so a pooled connection can never carry a
  previous request's restaurant.
- **Policies** on every tenant table: `restaurant_id = current_restaurant_id()` for
  reads and writes. No restaurant in context means `NULL`, which matches nothing.
- **Pre-login lookups** (find by email, email exists, find refresh token) have no
  restaurant yet. They use three `SECURITY DEFINER` functions that take one key and
  return one row or a boolean. They are the only cross-tenant door and are listed
  in one migration for review.
- **Sign-up** runs as the id of the restaurant it is about to create.
- **Guardrail test:** any table with `restaurant_id` that lacks RLS or a policy
  fails the build. A mutation check (switching RLS off on one table) was confirmed
  to fail 7 of 8 isolation tests.

## Consequences
- Pros: a forgotten filter in code cannot leak data; the guarantee is tested at the
  database level; new tables are forced to follow the rule.
- Cons: each pool checkout costs one extra round trip (`set_config`); the
  restaurant must be switched before a transaction opens, not during it; the three
  lookup functions must stay minimal.
- Residual risk: SQL injection could call `set_config` and switch restaurant. The
  code uses only parameterised queries, but RLS does not defend against injection
  by itself.

## Alternatives rejected
- A setting that disables RLS when unset: "forgetting" the context would then open
  everything instead of closing it.
- Setting the restaurant per transaction with an aspect: it misses code that reads
  outside a transaction.
- One database role: simpler, but then RLS would not apply.
