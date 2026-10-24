# 0001 - Java 21 and Spring Boot for the backend

**Status:** Accepted

## Context
The desktop RistoManager is written in Java, and its domain model, rules and
tests are meant to carry over. The project is also meant to be a realistic,
employable stack.

## Decision
Java 21 (the current long-term-support release) with Spring Boot 4.1, built with
Maven. Flyway manages schema changes. Plain JDBC (`JdbcClient`) is used in Phase 0;
JPA/Hibernate is introduced in Phase 1 when real entities appear.

## Consequences
- Pros: the existing domain code ports directly; Spring Boot is the dominant
  Java backend framework in the Italian market; Spring Security, Testcontainers
  and Actuator are mature.
- Cons: more boilerplate and a heavier startup than Node or Go; Spring has a lot
  of "magic" that must be learned deliberately.

## Alternatives rejected
- Node/NestJS: one language across the stack, but throws away the existing Java
  domain model.
- Kotlin: nicer language, smaller local job market, extra learning on top of Spring.
- Gradle instead of Maven: equally valid; Maven was already used here and its
  fixed structure is easier to read when learning.
