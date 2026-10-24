# Architecture Decision Records

Each file records one significant technical decision: the situation, what was
chosen, what it costs, and what was rejected. Records are never rewritten; if a
decision changes, a new record supersedes the old one.

| # | Decision | Status |
|---|----------|--------|
| [0001](0001-java-spring-boot-backend.md) | Java 21 and Spring Boot for the backend | Accepted |
| [0002](0002-modular-monolith.md) | One modular backend, not microservices | Accepted |
| [0003](0003-multi-tenancy.md) | Shared database, `restaurant_id` on every row, plus row-level security | Accepted |
| [0004](0004-nextjs-for-all-frontends.md) | Next.js for every frontend | Accepted |
| [0005](0005-server-sent-events-for-kitchen.md) | Server-Sent Events for the live kitchen screen | Accepted |
| [0006](0006-deployment-path.md) | Docker Compose first, k3s in Phase 3 | Accepted |
| [0007](0007-authentication.md) | Short-lived JWT plus rotating refresh cookie | Accepted |
