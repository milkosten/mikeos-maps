---
name: osmike-maps
description: Use when an agent needs public service health or a two-point car route with distance, estimated duration, geometry and directions.
---

Use the public OsMike Maps integration for stateless routing with known coordinates. No login or secret is needed. The supervisor registers the integration before these calls become available.

```bash
osmike-int osmike-maps test
osmike-int osmike-maps route --from '{"lat":59.3293,"lon":18.0686}' --to '{"lat":59.3326,"lon":18.0649}'
osmike-int osmike-maps route --from '{"lat":57.7089,"lon":11.9746}' --to '{"lat":57.7000,"lon":11.9500}'
osmike-int osmike-maps route --from '{"lat":55.6050,"lon":13.0038}' --to '{"lat":55.6100,"lon":13.0200}'
```

Pass decimal latitude and longitude in JSON objects, with latitude between -90 and 90 and longitude between -180 and 180. Route results include `ok`, `km`, `eta_min`, `router`, an encoded `polyline` (precision 5), and `steps`. Check `ok`: an HTTP 200 can still report routing failure. Times are estimates, without live traffic guarantees.

Only `GET /api/health` and stateless `POST /api/route` are exposed. POST computes a result without saving a trip and is a READ command. No searches, saved trips, interactions, congestion records or other personal endpoints are available through this integration. Do not send personal records.

Supports two-point car routes only; no geocoding, bicycle or walking profiles, multi-stop optimization, or map rendering. Supply coordinates obtained separately.

Keep routine calls below one per second; no bulk polling. Budgets are shared by callers per server process: 60 route requests and 600 health requests per 60 seconds. They are not distributed quotas across replicas. Inspect `X-RateLimit-Limit` and `X-RateLimit-Remaining`; on HTTP 429 wait for `Retry-After` before retrying. Prefer occasional health checks and reuse route results when suitable.
