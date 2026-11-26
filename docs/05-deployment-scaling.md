# Deployment and scaling for 1,000+ farms

## Sizing assumptions

| Indicator | Estimated value |
|---|--:|
| Farms | 1,000 |
| Parcels | 30,000 (30 per farm) |
| Users | 5,000 (5 per farm) |
| Peak Socket.IO connections | 1,500 |
| Peak API requests | 150-300 per second |
| Sensors | 2,000, with one reading every 10 minutes |
| IoT readings per year | About 105 million rows |

These values must be confirmed by a load test before launch.

## Production architecture

```text
Cloudflare / WAF
      │
Load balancer (TLS, WebSocket)
      │
API × 3-6 (JOBS_ENABLED=false)      Worker × 2 (JOBS_ENABLED=true)
      │                                   │
      ├────────── Managed Redis ──────────┤
      │                                   │
PgBouncer ──► PostgreSQL primary ──► read replica
                    │
              automatic backup + PITR
```

## Recommended starting configuration

| Component | Size | Notes |
|---|---|---|
| API | 3 instances, 2 vCPU, 4 GB | Autoscaling at 60% CPU |
| Worker | 2 instances, 2 vCPU, 4 GB | Redis locks prevent double runs |
| PostgreSQL | 8 vCPU, 32 GB, 500 GB SSD | With a replica in another zone |
| PgBouncer | Transaction mode | Add `pgbouncer=true` to `DATABASE_URL` |
| Redis | 4 GB, with a replica | Cache, Socket.IO, locks |
| File storage | S3 or Supabase Storage | For reports and archived images |

Kubernetes becomes useful above about 10 instances. Until then, a managed container service (AWS ECS, Google Cloud Run or Azure Container Apps) is simpler to operate. Cloud Run requires session affinity to be configured for WebSocket.

## Launch steps

1. Build the image: `docker build -t fermapp-api apps/api`
2. The migrations (Flyway) run when the first instance starts; with several instances starting together, start one first.
3. Start the API instances with `JOBS_ENABLED=false`.
4. Start the workers with `JOBS_ENABLED=true`.
5. Check `/health`, then shift traffic gradually.
6. Publish the mobile app with EAS Build and EAS Submit.

Secrets are kept in a secrets manager, and `JWT_ACCESS_SECRET` is rotated periodically.

## Changes needed as usage grows

| Threshold | Change |
|---|---|
| Before launch | Commercial licences for the weather and satellite services |
| Before launch | Group nearby parcels on a 0.05° grid for alerts |
| Above 100 farms | Move jobs to BullMQ, with retries and separate queues |
| Above 500 farms | Partition `IotReading` by month or migrate to TimescaleDB |
| Above 500 farms | Partition `Notification` and `WeatherAlert`, with 12-month retention |
| Spatial queries | Add PostGIS and a GiST index on the geometry |
| Above 2,000 farms | Read reports from the replica and move exports into jobs |

## External services at scale

With 30,000 parcels and a check every 30 minutes, calls without grouping would reach 1.44 million per day. Caching on a grid of about 1 km reduces this number only partially, so grouping on a larger grid is mandatory.

| Service | Caution |
|---|---|
| Open-Meteo | The free API is for non-commercial use. A commercial product needs a paid plan |
| OpenWeatherMap | The plan is chosen based on the number of calls resulting from grouping |
| SoilGrids | Service with no availability guarantee. Queries run once per parcel, and failures are retried automatically |
| NASA POWER | Results are cached for 7 days |
| Sentinel Hub / Copernicus | Usage is measured in processing units, so downloads must be scheduled per farm |
| Claude API | Monthly per-farm limits and token monitoring are required |
| Expo Push | Sent in batches of 100 notifications |

## Observability

- Centralized JSON logs in Grafana Loki, Datadog or CloudWatch.
- Metrics for latency, error rate, socket connections and job duration.
- Sentry for errors on the server and in the mobile app.
- Alerts for 5xx errors, delayed jobs, a lagging replica and disk space.

## Continuity

| Indicator | Target |
|---|---|
| Availability | 99.9% |
| Maximum data loss (RPO) | 5 minutes |
| Recovery time (RTO) | 1 hour |
| Backup retention | 30 days, with a monthly restore test |

## Security and compliance

- TLS everywhere and the database in a private network.
- EU hosting for farmers' data and data processing agreements with vendors.
- Data export and deletion on request, in line with GDPR.
- Automatic scanning of dependencies and Docker images in CI.
