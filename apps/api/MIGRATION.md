# API migration: TypeScript (Express + Prisma) to Java (Spring Boot + JPA)

The API was rewritten from Node.js to Java 21 / Spring Boot 3.5. The REST contract, the WebSocket
events, the database schema and the business rules are unchanged, so the mobile app and existing
databases work without changes. The TypeScript source is in git history (commit before this change).

Java paths below are relative to `src/main/java/com/fermapp/api/`.

## File mapping

### Application and infrastructure

| TypeScript | Java | Notes |
|---|---|---|
| `src/server.ts`, `src/app.ts` | `FermAppApplication.java`, `config/WebConfig.java`, `config/SecurityConfig.java`, `config/HealthController.java` | Middleware order kept: global rate limit, 2 MB JSON limit, per-route limit, authentication and roles, idempotency. helmet headers reproduced in `SecurityConfig`. |
| `src/config/env.ts` | `config/AppProperties.java`, `resources/application.yml`, `config/DatabaseUrlEnvironmentPostProcessor.java` | Same environment variables. `DATABASE_URL` in the Prisma form is converted to JDBC. `apps/api/.env` is still read in development. |
| `src/lib/route.ts` (ApiRouter) | `security/Access.java`, `security/AccessGroup.java`, `security/AccessInterceptor.java`, `common/web/ApiResponse.java`, `common/web/Idempotency*.java`, `common/web/RateLimit*.java`, `config/AccessDeclarationCheck.java` | See "No direct equivalent" below. |
| `src/lib/tokens.ts` | `security/JwtService.java`, `security/TokenHashing.java`, `modules/auth/SessionService.java` | Spring Security `NimbusJwtEncoder`/`Decoder`, HS256, issuer `fermapp`, no clock skew. Tokens from the old server stay valid. |
| `src/lib/errors.ts`, `src/middleware/error.ts` | `common/error/ApiException.java`, `common/error/Errors.java`, `common/error/ErrorBody.java`, `common/error/GlobalExceptionHandler.java` | `@RestControllerAdvice`. Prisma P2002 is PostgreSQL SQLState 23505 (409); P2025 is `RecordNotFoundException` (404). |
| `src/lib/access.ts` | `modules/common/AccessGuard.java` | |
| `src/lib/cache.ts` | `common/cache/CacheService.java` | Redis (Spring Data Redis) with a bounded in-memory fallback; in-flight loads shared with `CompletableFuture`. |
| `src/lib/http.ts` | `common/http/UpstreamHttp.java`, `common/util/Concurrency.java` | `java.net.http.HttpClient`, same retry statuses and back-off. `mapLimit` runs on virtual threads. |
| `src/lib/storage.ts` | `common/storage/FileStorage.java` | |
| `src/lib/openapi.ts`, `scripts/generate-docs.ts` | `config/OpenApiConfig.java` (springdoc) | Served at `/api/docs.json` and `/api/docs`. Each operation states its roles. |
| `src/lib/logger.ts` | SLF4J/Logback | |
| `src/lib/prisma.ts`, `prisma/schema.prisma` | `persistence/entity/*.java`, `persistence/repository/*.java` | 25 entities, one per model, with every relation (`@ManyToOne`, `@OneToMany`, `@OneToOne`) and unique constraints. |
| `prisma/migrations/*` | `resources/db/migration/V1..V5__*.sql`, `config/PrismaBaselineConfig.java` | Same SQL, run by Flyway. |
| `prisma/seed.ts` | `seed/DemoDataSeeder.java` | Runs when `SEED_DEMO_DATA=true`, never in production. |
| `scripts/dev-local.ts` | `../../scripts/dev-local.mjs` | Embedded PostgreSQL, then `mvnw spring-boot:run`. |
| `src/realtime/socket.ts` | `realtime/SocketIoHandler.java`, `realtime/RealtimeHub.java`, `realtime/RealtimeEvent.java`, `config/WebSocketConfig.java` | See below. |
| `src/jobs/scheduler.ts` | `jobs/Scheduler.java` | `@Scheduled`, same intervals and locks. |
| `src/types/modules.d.ts` | (not needed) | |

### Integrations

| TypeScript | Java |
|---|---|
| `integrations/openMeteo.ts` | `integrations/OpenMeteoClient.java` |
| `integrations/openWeather.ts` | `integrations/OpenWeatherClient.java` |
| `integrations/nasaPower.ts` | `integrations/NasaPowerClient.java` |
| `integrations/soilGrids.ts` | `integrations/SoilGridsClient.java` |
| `integrations/sentinel.ts` | `integrations/SentinelClient.java` |
| `@anthropic-ai/sdk` call in `modules/ai/ai.routes.ts` | `integrations/AnthropicAssistantClient.java` (official `anthropic-java` SDK; streaming, adaptive thinking, medium effort, cached system prompt, server-side refusal fallbacks) |
| Expo push in `modules/notifications/notifications.service.ts` | `integrations/ExpoPushClient.java` |

### Modules

| TypeScript | Java |
|---|---|
| `modules/auth/auth.routes.ts` | `modules/auth/AuthController.java`, `AuthService.java`, `AuthDtos.java`, `SessionService.java` |
| `modules/team/team.routes.ts` | `modules/team/TeamController.java` |
| `modules/dashboard/dashboard.routes.ts` | `modules/dashboard/DashboardController.java` |
| `modules/parcels/parcels.routes.ts` | `modules/parcels/ParcelController.java`, `ParcelService.java`, `ParcelDtos.java`, `PolygonInput.java` |
| `modules/parcels/geo-io.ts` | `modules/parcels/GeoIo.java` (KML via the JDK DOM parser, shapefiles via GeoTools with reprojection from `.prj`, archives checked with Commons Compress) |
| `modules/plans/plan.routes.ts`, `plan.service.ts` | `modules/plans/PlanController.java`, `PlanService.java` |
| `modules/crops/crops.routes.ts` | `modules/crops/CropController.java` |
| `modules/machines/machines.routes.ts` | `modules/machines/MachineController.java` |
| `modules/map/map.routes.ts` | `modules/map/MapController.java` |
| `modules/soil/soil.routes.ts`, `soil.service.ts` | `modules/soil/SoilController.java`, `SoilService.java` |
| `modules/satellite/satellite.routes.ts` | `modules/satellite/SatelliteController.java` |
| `modules/intelligence/intelligence.routes.ts`, `intelligence.service.ts` | `modules/intelligence/IntelligenceController.java`, `IntelligenceService.java`, `SoilSection.java` |
| `modules/work/work.routes.ts`, `work.service.ts` | `modules/work/WorkController.java`, `WorkService.java`, `WorkViews.java`, `RunProgress.java` |
| `modules/irrigation/irrigation.routes.ts`, `irrigation.service.ts` | `modules/irrigation/IrrigationController.java`, `IrrigationService.java` |
| `modules/tasks/tasks.routes.ts` | `modules/tasks/TaskController.java` |
| `modules/weather/weather.routes.ts`, `weather.service.ts` | `modules/weather/WeatherController.java`, `WeatherService.java` |
| `modules/costs/costs.routes.ts` | `modules/costs/CostController.java` |
| `modules/economics/economics.routes.ts`, `economics.service.ts` | `modules/economics/EconomicsController.java`, `EconomicsService.java` |
| `modules/reports/reports.routes.ts`, `reports.service.ts` | `modules/reports/ReportController.java`, `ReportService.java`, `ReportRenderer.java` (PDFBox replaces pdfkit, Apache POI replaces ExcelJS) |
| `modules/ai/ai.routes.ts`, `ai.context.ts` | `modules/ai/AiController.java`, `ParcelContextService.java` |
| `modules/iot/iot.routes.ts`, `iot.service.ts`, `mqtt.bridge.ts` | `modules/iot/IotController.java`, `IotService.java`, `MqttBridge.java` (Eclipse Paho) |
| `modules/notifications/*` | `modules/notifications/NotificationController.java`, `NotificationService.java` |
| Prisma result shapes | `modules/common/Views.java`, `modules/work/WorkViews.java` |

### Shared domain (`packages/domain`, the parts the server uses)

| TypeScript | Java |
|---|---|
| `crops.ts` | `domain/crops/Crops.java` |
| `recommendations.ts`, `water.ts`, `gdd.ts`, `vegetation.ts` | `domain/crops/CropRules.java` |
| `geo.ts` | `domain/geo/Geo.java` |
| `rotation.ts` | `domain/rotation/Rotation.java` |
| `work.ts`, `irrigation.ts` | `domain/work/WorkMath.java` |
| `windows.ts`, `alerts.ts` | `domain/weather/WeatherRules.java` |
| `soil.ts`, `soilIndices` from `intelligence.ts` | `domain/soil/SoilRules.java` |
| `intelligence.ts`, `costs.ts` | `domain/intelligence/Intelligence.java` |
| `parcelPlan.ts` | `domain/plan/ParcelPlanner.java` |
| `tillage.ts` | `domain/tillage/Tillage.java` |
| `money.ts` | `domain/economics/Money.java` |
| `economics.ts` | `domain/economics/Economics.java`, `EconomicModels.java` |
| `catalog.ts` (implement catalog, diesel price) | `domain/economics/ImplementCatalog.java` |
| `fieldStatus.ts` | `domain/fieldstatus/FieldStatusRules.java` |
| `reportBuilders.ts` | `domain/report/ReportBuilders.java`, `ReportTable.java` |
| Prisma enums | `domain/model/*.java` (16 enums) |

`packages/domain` stays in TypeScript for the mobile app. The copilot, calendar, field coverage,
field path, satellite and weather-brief files, and the seed and tractor catalogs, are used only by
the phone and were not ported.

## Major decisions

- **Contract first.** Every response keeps the `{ "data": ... }` envelope, the same status codes,
  the same error codes and messages, and the field names and order of the Prisma objects. Dates are
  written with three fraction digits (`2026-11-26T09:00:00.000Z`), integral numbers without `.0`,
  BigInt ids as strings, and Decimal columns as numbers where the routes converted them and as
  strings where Prisma returned them unconverted (parcel details).
- **Records for DTOs and views**, entities only for persistence. Entities are never serialised, so
  lazy loading and cycles cannot leak into responses.
- **Synchronous services on virtual threads** instead of `async/await`. `Promise.all` became
  `Concurrency.both` and `mapLimit` where the parallelism mattered (external calls).
- **Validation with Jakarta Validation**, read as strictly as zod: no string-to-number or
  float-to-int coercion, unknown fields ignored, strings trimmed in compact constructors, PostgreSQL
  enums validated by the Java enums.
- **Same database.** Flyway runs the original migration SQL. A database created by Prisma is
  baselined at its last applied Prisma migration, and only the missing ones run.
- **Background jobs** use `@Scheduled` with the same intervals and the same Redis/in-memory locks,
  so only one instance runs each job. `JOBS_ENABLED=false` still turns them off.

## No direct equivalent in Java, and the chosen implementation

| TypeScript feature | Problem | Java implementation |
|---|---|---|
| `ApiRouter` with route specs (roles, schemas, status, limiter, upload) | Spring declares these in different places | `@Access` + `AccessInterceptor` for roles (before the multipart body is read), `@RateLimited` for limiters, `ApiResponse` for the status, Jakarta Validation for schemas, and a startup check that rejects any endpoint without `@Access`. |
| `undefined` vs `null` in PATCH bodies (Prisma ignores undefined) | Java has only `null` | `JsonNullable<T>` (jackson-databind-nullable) with a Jakarta value extractor (`config/JsonNullableExtractor.java`): absent leaves the column unchanged, `null` clears it. |
| Idempotency inside the router | Spring has no single place that sees auth, handler and response | `IdempotencyInterceptor` reserves or replays after authentication; `IdempotencyFilter` stores the final status and body from a `ContentCachingResponseWrapper`. |
| socket.io server | No Java server shares the Tomcat port with full socket.io v4 support | `SocketIoHandler` implements Engine.IO 4 / Socket.IO 5 over Spring WebSocket on `/socket.io/`: handshake, ping/pong, CONNECT with auth (`connect_error` "UNAUTHORIZED"), events, acknowledgements on `/iot`. The Redis adapter became a Redis pub/sub relay in `RealtimeHub`. Tested with the app's `socket.io-client`. |
| `z.coerce.date()` | Jackson and Spring accept only ISO instants | `JsonFormats.LenientInstantDeserializer` and a query converter accept dates, date-times with or without offset, and epoch milliseconds. |
| Number formatting (`String(n)`, `toFixed`) in messages | Java prints `6.0` and rounds differently | `common/util/Js.java`: `num` prints like JavaScript, `toFixed` uses the exact binary value with half-up rounding, `Math.round` semantics are the same. |
| Day boundaries with `getFullYear()` / `getUTCFullYear()` | Java needs an explicit zone | `common/util/Times.java` keeps local-time and UTC variants exactly where the TypeScript code used each. |
| bcryptjs truncating passwords at 72 bytes | Spring Security rejects longer passwords | `PasswordHasher` truncates on a character boundary, so existing hashes and long passwords behave as before. |
| PostgreSQL enum types | JPQL enum literals render as an unquoted cast that PostgreSQL folds to lower case | Statuses are always bound as parameters in the repositories. |

## Known differences

- Validation messages for individual fields come from Jakarta Validation (for example
  "size must be between 1 and 72") instead of zod's wording. Codes, top-level messages and the
  `details[].field` paths are the same.
- socket.io clients must use the WebSocket transport (`transports: ['websocket']`), as the mobile
  app already does. HTTP long-polling is not implemented.
- The OpenAPI document is generated by springdoc; it describes the same endpoints and roles, but
  its schemas are derived from the Java types.

## Port to Kotlin (in progress)

The API is moving from Java to Kotlin one file at a time. Both languages compile in the same Maven
module: `kotlin-maven-plugin` builds `src/main/kotlin` and `src/main/java` first, then javac builds
the Java files against the Kotlin classes, so either language can call the other.

Rules for each ported file:

- Move it with `git mv` from `src/main/java/...` to the same package under `src/main/kotlin/...`.
- Keep the Java API while Java callers remain: `object` + `@JvmStatic` for static helpers,
  `@JvmRecord data class` for records (same accessors and same JSON).
- The `spring`, `jpa` and `lombok` compiler plugins are on, so Kotlin can extend Spring beans,
  declare entities and read Lombok accessors of Java entities.
- Keep a reference type nullable wherever the Java code accepted or produced null. Kotlin checks
  non-null parameters at runtime, so a wrong guess turns a working Java call into an exception.
  Types read from JSON (request bodies, the Redis cache, JSON columns) need the most care.
- Sum doubles with `sumOfDoubles` (`domain/Sums.kt`) where Java used `DoubleStream.sum()`:
  it uses compensated summation, and Kotlin's `sumOf` can differ in the last bit.
- An interface that Java records implement keeps record-style methods (`time()`, not a
  `time` property); see `WeatherRules.HourlyWeather`.
- Keep `@Throws` where the Java method declared a checked exception that Java callers catch
  (`FileStorage.load`, `RateLimiter.allow`): without it, their `catch` no longer compiles.
- `Times.iso` takes and returns nullable values, as before. Kotlin code holding a non-null
  instant uses `instant.toIsoString()`; there is deliberately no non-null overload of `iso`,
  which Kotlin would pick for values of unknown nullability coming from Java.
- New tests can go in `src/test/kotlin`.

Snapshot tests record what the Java code did before each step, with the production ObjectMapper,
in `src/test/resources/snapshots/`: `DomainSnapshotTest` (every domain rule),
`CommonSnapshotTest` (helpers, errors, rate limiting, body limit, idempotency, cache, storage, the
upstream HTTP client against a local server), `ExceptionHandlerSnapshotTest` (every error
response) and `IntegrationSnapshotTest` (the requests each external client sends and what it makes
of the answers). A port must leave them unchanged; after an intended change, record them again with
`./mvnw test -Dsnapshot.update=true` and review the diff. Write the snapshot test for the next area
before porting it.

Order: domain rules, then utilities and integrations, then services and controllers, and the
JPA entities last (they use Lombok).

| Area | Status |
|---|---|
| `domain/*` (all rules and enums) | Kotlin |
| `common/*` (helpers, errors, JSON, web filters, cache, HTTP, storage) | Kotlin |
| `integrations/*` (Open-Meteo, OpenWeatherMap, NASA POWER, SoilGrids, Sentinel, Expo, Anthropic) | Kotlin |
| `config`, `security`, `modules`, `jobs`, `realtime`, `seed`, `persistence` | Java |

Changes in behaviour made during the port, each on purpose:

- Report columns carry `numeric` and `money` in the JSON again, as the TypeScript API sent them.
  The Java record lost both because `@JsonIgnore` on `isNumeric()`/`isMoney()` hid the whole
  property, so the phone left-aligned numbers and did not format money columns.
