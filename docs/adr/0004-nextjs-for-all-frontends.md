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
