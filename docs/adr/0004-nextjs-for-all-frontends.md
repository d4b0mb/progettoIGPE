# 0004 - Next.js for every frontend

**Status:** Accepted (supersedes the Angular + Next.js plan in the master brief)

## Context
The brief planned Angular for the back-office and kitchen screen and Next.js for
the public booking site. That means two frameworks, two ecosystems and two sets of
shared components.

## Decision
Next.js (React, TypeScript) for the back-office, kitchen screen and public site,
in one `frontend/` workspace with shared UI components and an API client generated
from the backend's OpenAPI spec. Frontends arrive from Phase 1.

## Consequences
- Pros: one language and toolset to learn; shared components; React has the widest
  job market; server rendering covers the SEO needs of the public site.
- Cons: Angular's strong structure for big admin apps is given up; Next.js
  changes quickly, so versions must be pinned and upgrades planned.

## As built (Block 5)
- **One Next.js app, not three.** Back-office, kitchen screen and (later) the public
  site are route groups of one app in `frontend/`. They share components, i18n and
  the API client, and deploy as one image. They can be split later if the public
  site's traffic ever needs to scale apart from the staff tools.
- **Same-origin API.** The browser only talks to the Next.js origin; `/api/*` is
  forwarded to the backend (a rewrite here, a reverse proxy in production). This
  keeps the login refresh cookie first-party and `SameSite=Strict`, and removes CORS.
- **Typed from the contract.** The backend publishes an OpenAPI description, committed
  as `frontend/openapi/openapi.json` and checked by a backend test. The frontend
  generates its types from it, and CI fails if they drift. A backend change that
  would break the frontend breaks the build instead.
- **Access token in memory only.** The refresh token is an HttpOnly cookie. Because
  refresh tokens rotate, refreshes are serialised (Web Locks across tabs, one shared
  request inside a tab) so two simultaneous refreshes cannot look like token theft.
- **Languages.** Italian (default) and English, chosen with a cookie. Text lives in
  `frontend/messages/*.json`.
- **Fonts are self-hosted** (`@fontsource`), so a visitor's browser never contacts
  Google: simpler GDPR story and one less dependency at runtime.
- **Cache Components and partial prefetching are off.** The staff tools are
  client-driven behind a login, so server-side caching adds constraints for no gain.
  To be revisited for the public booking site.
- **Mobile first.** Navigation is a bottom bar on phones and a sidebar on desktop;
  controls are at least 44 px tall.
