# API overview

These paths are taken from the Spring controllers. Request/response schemas live in `backend/src/main/java/com/nowserving/dto/`; the typed client is in `frontend/src/api/`.

| Area | Representative endpoints | Access |
| --- | --- | --- |
| Authentication | `POST /auth/signup`, `/auth/login`, `/auth/google`; `GET /me` | Public login; authenticated identity |
| Owner queues | `GET/POST /queues`, `POST /queues/{id}/advance` | Owner JWT |
| Join | `POST /public/queues/{joinToken}/entries` | Queue token; optional `Idempotency-Key` header |
| Ticket | `GET/DELETE /public/entries/{entryToken}` | Customer ticket token |
| Reservations | `POST /public/queues/{joinToken}/reservations` | Queue token; optional `Idempotency-Key` header |
| Location | `POST/DELETE /public/entries/{entryToken}/location` | Customer ticket token |
| Departure advice | `GET /public/entries/{entryToken}/leave-now` | Customer ticket token |
| Discovery | `GET /public/venues/nearby` | Public, rate-limited |

Owner resources are scoped to the business identified by the authenticated session. Customer tokens do not grant owner access. Consult the controllers for validation, optional query parameters, and error responses; this document is an overview, not a generated OpenAPI specification.
