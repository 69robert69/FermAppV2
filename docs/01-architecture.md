# Arhitectură

## Imagine de ansamblu

```text
Aplicație mobilă (Expo)
  SQLite: cache + coadă offline
  Zustand, TanStack Query
        │ HTTPS REST + Socket.IO
        ▼
API Express (instanțe fără stare) ──── Redis: cache, blocări, Socket.IO
        │                         └─── Joburi: progres, alerte, mementouri
        ▼
PostgreSQL (Prisma)

Servicii externe: Open-Meteo, OpenWeatherMap, SoilGrids, NASA POWER,
Copernicus Sentinel-2, Claude API, Expo Push
Senzori: MQTT, REST, WebSocket ──► același punct de ingestie
```

Regulile agronomice stau în `packages/domain`, fără dependențe. Serverul și telefonul folosesc același cod pentru suprafețe, capacitate de lucru, rotație și ETA. Estimările rămân identice online și offline.

## Decizii principale

| Decizie | Motiv |
|---|---|
| Monorepo cu pachet de domeniu comun | O singură sursă pentru formulele agronomice |
| PostgreSQL în Docker | Rulează identic local și în cloud, fără dependență de un furnizor |
| Geometrie GeoJSON în coloană JSON | Suficientă pentru funcțiile actuale. PostGIS se adaugă când apar interogări spațiale |
| Fermă activă în token, cu apartenență la mai multe ferme | Un consultant agronom poate lucra pentru mai mulți clienți |
| Chei de idempotență pentru modificări | O operațiune offline retrimisă nu se dublează |
| Progres calculat din timp | Lucrările și irigările rămân corecte după repornirea serverului sau a telefonului |
| Fără date inventate | Serviciile neconfigurate returnează o stare clară |

## Structura backend

```text
apps/api/
├── Dockerfile
├── prisma/
│   ├── schema.prisma            modelele bazei de date
│   ├── migrations/              migrările SQL
│   └── seed.ts                  date demo
├── scripts/generate-docs.ts     OpenAPI și lista de endpointuri
├── src/
│   ├── app.ts                   middleware, rute, Swagger
│   ├── server.ts                pornire și oprire controlată
│   ├── config/env.ts            validarea configurației
│   ├── integrations/
│   │   ├── openMeteo.ts         prognoză și istoric
│   │   ├── openWeather.ts       prognoză pentru alerte
│   │   ├── soilGrids.ts         profil de sol
│   │   ├── nasaPower.ts         climă și GDD
│   │   └── sentinel.ts          NDVI, NDRE, imagini
│   ├── jobs/scheduler.ts        progres, mementouri, alerte
│   ├── lib/
│   │   ├── route.ts             rute validate, roluri, idempotență, OpenAPI
│   │   ├── openapi.ts           documentul Swagger
│   │   ├── access.ts            verificări de apartenență la fermă
│   │   ├── cache.ts             Redis cu rezervă în memorie
│   │   ├── errors.ts, http.ts, logger.ts, prisma.ts, tokens.ts
│   ├── middleware/error.ts      format unitar pentru erori
│   ├── realtime/socket.ts       camere pe fermă, spațiul /iot
│   └── modules/
│       ├── auth/  team/  dashboard/  notifications/
│       ├── parcels/             CRUD, GeoJSON, KML, KMZ, SHP, LPIS
│       ├── crops/               istoric, rotație, recomandări, climă
│       ├── machines/  work/  tasks/  irrigation/
│       ├── weather/  soil/  satellite/
│       ├── costs/  reports/  ai/  iot/
└── test/                        teste de integrare pe PostgreSQL real
```

## Structura frontend

```text
apps/mobile/
├── App.tsx, index.ts, app.config.ts, metro.config.js
└── src/
    ├── api/            client HTTP, tipuri, TanStack Query
    ├── components/     ui, ParcelShape, RunCard, pickers
    ├── lib/            formatare și hook-uri
    ├── navigation/     cele 7 tab-uri și ecranele secundare
    ├── notifications/  înregistrare push
    ├── offline/        SQLite, coadă, sincronizare, sarcină de fundal
    ├── realtime/       evenimente Socket.IO
    ├── screens/
    │   ├── AuthScreen, DashboardScreen, MapScreen
    │   ├── ParcelEditorScreen, ParcelDetailScreen
    │   ├── WorkScreen, MachinesScreen, TasksScreen
    │   ├── IrrigationScreen, ReportsScreen
    │   └── AssistantScreen, SettingsScreen
    ├── store/auth.ts   sesiune în stocarea securizată
    └── theme.ts        culori, fonturi, spațiere
```

## Acoperirea modulelor

| # | Modul | Server | Mobil |
|---|---|---|---|
| 1 | Parcele și hartă | `modules/parcels` | `MapScreen`, `ParcelEditorScreen` |
| 2 | Istoric culturi | `modules/crops` | Parcelă, tab Culturi |
| 3 | Motor de rotație | `domain/rotation.ts` | Parcelă, tab Rotație |
| 4 | Utilaje | `modules/machines` | `MachinesScreen` |
| 5 | Work | `modules/work` | `WorkScreen` |
| 6 | To Do | `modules/tasks` | `TasksScreen` |
| 7 | Irigații | `modules/irrigation` | `IrrigationScreen` |
| 8 | Open-Meteo | `integrations/openMeteo.ts` | Parcelă, tab Meteo |
| 9 | OpenWeatherMap și alerte | `modules/weather` | Dashboard, notificări |
| 10 | Analiza solului | `modules/soil` | Parcelă, tab Sol |
| 11 | Recomandări culturi | `domain/recommendations.ts` | Parcelă, tab Rotație |
| 12 | NASA POWER | `integrations/nasaPower.ts` | Recomandări și API `/climate`, `/development` |
| 13 | Ferestre de lucru | `domain/windows.ts` | Parcelă, tab Ferestre |
| 14 | Necesar apă | `domain/water.ts` | Parcelă, tab Apă |
| 15 | Satelit și NDVI | `modules/satellite` | Parcelă, tab Satelit, culori pe hartă |
| 16 | Costuri | `modules/costs` | Parcelă, tab Costuri |
| 17 | Rapoarte PDF și Excel | `modules/reports` | `ReportsScreen` |
| 18 | Asistent AI | `modules/ai` | `AssistantScreen` |
| 19 | IoT | `modules/iot` | Doar API, pregătit pentru integrare |

## Roluri și permisiuni

| Acțiune | Administrator | Manager Fermă | Consultant Agronom | Operator Utilaj |
|---|:--:|:--:|:--:|:--:|
| Vede parcele, meteo, sol, satelit | ✓ | ✓ | ✓ | ✓ |
| Creează, modifică, arhivează parcele | ✓ | ✓ | | |
| Gestionează utilaje și senzori | ✓ | ✓ | | |
| Completează istoricul culturilor | ✓ | ✓ | ✓ | |
| Creează task-uri | ✓ | ✓ | ✓ | |
| Schimbă statusul task-urilor | ✓ | ✓ | ✓ | Doar ale lui |
| Pornește lucrări | ✓ | ✓ | | Doar pentru el |
| Controlează lucrări | ✓ | ✓ | | Doar ale lui |
| Pornește și controlează irigări | ✓ | ✓ | | ✓ |
| Vede costuri | ✓ | ✓ | ✓ | |
| Modifică costuri | ✓ | ✓ | | |
| Gestionează echipa | ✓ | | | |

## Securitate

- Parolele se păstrează cu bcrypt, iar tokenurile de refresh doar ca hash.
- Tokenurile de refresh se rotesc, iar reutilizarea unuia revocă toată sesiunea.
- Fiecare interogare se limitează la ferma din token.
- Limitele de cereri protejează autentificarea, asistentul AI și ingestia IoT.
- Importurile au limite de dimensiune și sunt verificate înainte de dezarhivare.
- Cheile senzorilor se afișează o singură dată și se salvează doar ca hash.
- Retragerea accesului la o fermă devine completă la expirarea tokenului de acces, în maximum 15 minute.
