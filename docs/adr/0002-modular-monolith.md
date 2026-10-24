# 0002 - One modular backend, not microservices

**Status:** Accepted

## Context
The system has distinct areas (floor plan, reservations, orders, staff) but a
single small team and a single deployable need.

## Decision
One Spring Boot application, organised in packages by business area. Areas talk
through small public interfaces, not by reaching into each other's tables. Module
boundaries may later be enforced with Spring Modulith.

## Consequences
- Pros: one deployment, one database transaction when needed, simple local
  development, easy debugging. Boundaries can later be split into services if
  scale ever demands it.
- Cons: discipline is required to keep modules separate; one bad module can take
  the whole process down.

## Alternatives rejected
- Microservices: network calls, distributed data and deployment complexity with
  no benefit at this size.
