# 0003 - Shared database, `restaurant_id` on every row, plus row-level security

**Status:** Accepted

## Context
Many restaurants use one service. The failure that would kill the product is one
restaurant seeing another's data.

## Decision
All restaurants share one PostgreSQL database. Every tenant-owned row carries
`restaurant_id`. The backend filters by it on every query (lock 1). From Phase 1,
PostgreSQL row-level security enforces it again in the database (lock 2). Automated
isolation tests prove user A cannot read or change restaurant B's data.

## Consequences
- Pros: cheap to run, one schema to migrate, scales to many small tenants; two
  independent locks mean one bug is not enough to leak data.
- Cons: noisy-neighbour risk (one busy tenant slows others); row-level security
  adds setup (a per-request tenant setting) and must be tested carefully.

## Alternatives rejected
- Database per restaurant: strongest isolation, but migrations and operations
  multiply per tenant. Overkill for small restaurants.
- Schema per restaurant: middle ground, still painful migrations at scale.
