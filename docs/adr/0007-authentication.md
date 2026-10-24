# 0007 - Authentication: short-lived JWT plus rotating refresh cookie

**Status:** Accepted

## Context
The API serves a web app and must scale to several copies without shared memory.
Staff log in with email and password; each user belongs to one restaurant and has
one of four roles.

## Decision
- Passwords are hashed with BCrypt. Passwords are limited to 10-72 characters:
  BCrypt reads only the first 72 bytes, so longer ones are refused, not truncated.
- Login returns a signed JWT (HS256, 15 minutes) carrying user id, restaurant id
  and role. Every request is verified from the token alone, with no database or
  session lookup.
- A refresh token (random, 14 days) lives in an `HttpOnly`, `SameSite=Strict`
  cookie scoped to `/api/v1/auth`, so scripts in the page cannot read it. Only its
  SHA-256 hash is stored.
- Each refresh rotates the token. Presenting an already-used token is treated as
  theft and revokes every token of that user.
- Roles are checked on the server (`@PreAuthorize`). The restaurant id always
  comes from the verified token, never from request input.
- Login does equal work for unknown emails and wrong passwords, with an identical
  response, so attackers cannot discover which emails have accounts.

## Consequences
- Pros: stateless, so more API copies need no session sharing; a stolen access
  token expires quickly; a stolen refresh token is detected on reuse; the browser
  never exposes the long-lived secret to scripts.
- Cons: an access token cannot be revoked, so a deactivated user keeps access for
  up to 15 minutes (their refresh tokens are revoked immediately); HS256 uses one
  shared secret, which is fine for one service but would be replaced by asymmetric
  keys if other services had to verify tokens.
- Not yet done: login rate limiting and account lockout, password reset by email.

## Alternatives rejected
- Server-side sessions: simple, but need shared storage as soon as there are two
  API copies.
- Tokens in `localStorage`: easier, but any script injected into the page can steal
  them.
- Keycloak or another identity server: powerful, but heavy for one product and it
  hides what this project is meant to teach.
