# FermApp

Platformă de management agricol: parcele pe hartă, rotația culturilor, lucrări și irigații în timp real, meteo, sol, imagini satelitare, costuri, rapoarte și asistent agronomic AI.

| Pachet | Rol |
|---|---|
| [apps/mobile](apps/mobile) | Aplicația Expo (React Native, TypeScript) |
| [apps/api](apps/api) | API Express, Prisma, PostgreSQL, Redis și Socket.IO |
| [packages/domain](packages/domain) | Regulile agronomice, folosite de server și de telefon |
| [docs](docs) | Arhitectură, API, fluxuri, plan și deployment |

## Pornire rapidă pe Windows

1. Instalează aplicația **Expo Go** pe telefon.
2. Conectează telefonul la aceeași rețea cu calculatorul.
3. Dă dublu-click pe `Porneste-FermApp.bat` și lasă deschise cele două ferestre.
4. Scanează codul QR din fereastra „FermApp - Telefon”.
5. Intră cu `demo@fermapp.local` și parola `FermApp-demo-2026`.

## Pornire manuală

Ai nevoie de Node.js 22 sau mai nou și de PostgreSQL. Redis este opțional. Cu Docker, pornești serviciile astfel:

```bash
docker compose up -d postgres redis
```

Instalează și pregătește API-ul:

```bash
npm install
cp apps/api/.env.example apps/api/.env
npm run db:generate
npm run db:deploy -w @fermapp/api
npm run db:seed
npm run dev:api
```

API-ul pornește la `http://localhost:4000`, iar Swagger la `http://localhost:4000/api/docs`.

Contul demo este `demo@fermapp.local`, cu parola `FermApp-demo-2026`.

Pornește aplicația mobilă într-un alt terminal:

```bash
cp apps/mobile/.env.example apps/mobile/.env
npm run dev:mobile
```

Hărțile, notificările push și sarcinile de fundal au nevoie de un development build:

```bash
cd apps/mobile
npx eas build --profile development --platform android
```

## Chei pentru servicii externe

Open-Meteo, SoilGrids și NASA POWER funcționează fără cheie. Celelalte se configurează în `apps/api/.env`.

| Variabilă | Activează | Fără cheie |
|---|---|---|
| `OPENWEATHER_API_KEY` | Alerte OpenWeatherMap | Alertele folosesc Open-Meteo |
| `SENTINEL_CLIENT_ID`, `SENTINEL_CLIENT_SECRET` | NDVI, NDRE și Health Index | Ecranul Satelit explică lipsa configurării |
| `ANTHROPIC_API_KEY` | Asistentul AI | Asistentul oferă un rezumat calculat din datele parcelei |
| `GOOGLE_MAPS_API_KEY` (mobil) | Hărți în build-urile native | Harta nu se încarcă pe Android |

## Verificări

```bash
npm test            # teste de domeniu și de integrare API
npm run typecheck   # tipuri pentru toate pachetele
npm run bundle:check -w @fermapp/mobile
npm run docs:api    # regenerează docs/openapi.json și lista de endpointuri
```

Testele API pornesc automat un PostgreSQL temporar, deci nu au nevoie de Docker.

## Documentație

1. [Arhitectură, structură și roluri](docs/01-arhitectura.md)
2. [Endpointuri API](docs/02-endpointuri-api.md)
3. [Fluxuri și wireframe-uri](docs/03-fluxuri-wireframes.md)
4. [Plan de implementare și estimări](docs/04-plan-estimari.md)
5. [Deployment și scalare pentru 1.000+ ferme](docs/05-deployment-scalare.md)
6. [Schema PostgreSQL](docs/schema.sql) și [modelele Prisma](apps/api/prisma/schema.prisma)
