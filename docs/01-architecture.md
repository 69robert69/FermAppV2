# Architecture

## Overview

```text
Mobile app (Expo)
  SQLite: cache + offline queue
  Zustand, TanStack Query
        │ HTTPS REST + Socket.IO
        ▼
Spring Boot API (stateless instances) ──── Redis: cache, locks, Socket.IO
        │                         └─── Jobs: progress, alerts, reminders
        ▼
PostgreSQL (JPA + Flyway)

External services: Open-Meteo, OpenWeatherMap, SoilGrids, NASA POWER,
Copernicus Sentinel-2, Claude API, Expo Push
Sensors: MQTT, REST, WebSocket ──► the same ingestion point
```

The agronomic rules live in `packages/domain` (TypeScript, used by the phone) and in `com.fermapp.api.domain` (Java, a line-by-line port used by the server). Both give the same areas, work capacity, rotation and ETA, so estimates stay identical online and offline; a change to a rule is made in both.

## Main decisions

| Decision | Reason |
|---|---|
| Monorepo with a shared domain package | A single source for the agronomic formulas |
| PostgreSQL in Docker | Runs the same locally and in the cloud, with no vendor lock-in |
| GeoJSON geometry in a JSON column | Enough for the current features. PostGIS is added when spatial queries appear |
| Active farm in the token, with membership in several farms | An agronomist consultant can work for several clients |
| Idempotency keys for changes | A resent offline operation is not duplicated |
| Progress calculated from time | Field work and irrigation stay correct after the server or the phone restarts |
| No invented data | Unconfigured services return a clear status |

## Backend structure

```text
apps/api/                          Java 21, Spring Boot 3.5, Maven (mvnw)
├── Dockerfile, pom.xml, MIGRATION.md
└── src/
    ├── main/resources/
    │   ├── application.yml        configuration from the same environment variables
    │   ├── db/migration/          Flyway migrations V1..V5 (the former Prisma migrations)
    │   └── fonts/                 DejaVu Sans for PDF reports
    ├── main/java/com/fermapp/api/
    │   ├── config/                security, JSON, web pipeline, OpenAPI, WebSocket, Prisma takeover
    │   ├── common/                errors, envelope, rate limits, idempotency, cache, HTTP, storage
    │   ├── security/              JWT (Spring Security, Nimbus HS256), roles, bcrypt
    │   ├── domain/                agronomic rules (port of packages/domain)
    │   ├── persistence/           JPA entities and Spring Data repositories
    │   ├── integrations/          Open-Meteo, OpenWeatherMap, SoilGrids, NASA POWER, Sentinel, Claude, Expo Push
    │   ├── realtime/              Socket.IO v4 protocol over Spring WebSocket, farm rooms, /iot
    │   ├── jobs/                  progress, reminders, alerts, soil retry, cleanup
    │   ├── seed/                  demo data (SEED_DEMO_DATA=true)
    │   └── modules/               auth, team, dashboard, parcels, plans, crops, machines, work, tasks,
    │                              irrigation, weather, soil, satellite, intelligence, costs,
    │                              economics, reports, ai, iot, notifications, map
    └── test/                      domain, security, JSON and web-layer tests
```

## Frontend structure

```text
apps/mobile/
├── App.tsx, index.ts, app.config.ts, metro.config.js
└── src/
    ├── api/            HTTP client, types, TanStack Query
    ├── components/     ui, ParcelShape, RunCard, pickers
    ├── lib/            formatting and hooks
    ├── navigation/     the 7 tabs and the secondary screens
    ├── notifications/  push registration
    ├── offline/        SQLite, queue, sync, background task
    ├── realtime/       Socket.IO events
    ├── screens/
    │   ├── AuthScreen, DashboardScreen, MapScreen
    │   ├── ParcelEditorScreen, ParcelDetailScreen
    │   ├── WorkScreen, MachinesScreen, TasksScreen
    │   ├── IrrigationScreen, ReportsScreen
    │   └── AssistantScreen, SettingsScreen
    ├── store/auth.ts   session in secure storage
    └── theme.ts        colours, fonts, spacing
```

## Module coverage

| # | Module | Server | Mobile |
|---|---|---|---|
| 1 | Parcels and map | `modules/parcels` | `MapScreen`, `ParcelEditorScreen` |
| 2 | Crop history | `modules/crops` | Parcel, Crops tab |
| 3 | Rotation engine | `domain/rotation.ts` | Parcel, Rotation tab |
| 4 | Machines | `modules/machines` | `MachinesScreen` |
| 5 | Work | `modules/work` | `WorkScreen` |
| 6 | To Do | `modules/tasks` | `TasksScreen` |
| 7 | Irrigation | `modules/irrigation` | `IrrigationScreen` |
| 8 | Open-Meteo | `integrations/openMeteo.ts` | Parcel, Weather tab |
| 9 | OpenWeatherMap and alerts | `modules/weather` | Dashboard, notifications |
| 10 | Soil analysis | `modules/soil` | Parcel, Soil tab |
| 11 | Crop recommendations | `domain/recommendations.ts` | Parcel, Rotation tab |
| 12 | NASA POWER | `integrations/nasaPower.ts` | Recommendations and the `/climate`, `/development` API |
| 13 | Work windows | `domain/windows.ts` | Parcel, Work windows tab |
| 14 | Water requirement | `domain/water.ts` | Parcel, Water tab |
| 15 | Satellite and NDVI | `modules/satellite` | Parcel, Satellite tab, colours on the map |
| 16 | Costs | `modules/costs` | Parcel, Costs tab |
| 17 | PDF and Excel reports | `modules/reports` | `ReportsScreen` |
| 18 | AI assistant | `modules/ai` | `AssistantScreen` |
| 19 | IoT | `modules/iot` | API only, ready for integration |

## Roles and permissions

| Action | Administrator | Farm Manager | Agronomist Consultant | Machine Operator |
|---|:--:|:--:|:--:|:--:|
| Views parcels, weather, soil, satellite | ✓ | ✓ | ✓ | ✓ |
| Creates, edits, archives parcels | ✓ | ✓ | | |
| Manages machines and sensors | ✓ | ✓ | | |
| Fills in the crop history | ✓ | ✓ | ✓ | |
| Creates tasks | ✓ | ✓ | ✓ | |
| Changes task status | ✓ | ✓ | ✓ | Own tasks only |
| Starts operations | ✓ | ✓ | | For themselves only |
| Controls operations | ✓ | ✓ | | Own operations only |
| Starts and controls irrigation | ✓ | ✓ | | ✓ |
| Views costs | ✓ | ✓ | ✓ | |
| Edits costs | ✓ | ✓ | | |
| Manages the team | ✓ | | | |

## Security

- Passwords are stored with bcrypt, and refresh tokens only as hashes.
- Refresh tokens are rotated, and reusing one revokes the whole session.
- Every query is restricted to the farm in the token.
- Rate limits protect authentication, the AI assistant and IoT ingestion.
- Imports have size limits and are checked before extraction.
- Sensor keys are shown only once and stored only as hashes.
- Revoking access to a farm becomes complete when the access token expires, within 15 minutes at most.
