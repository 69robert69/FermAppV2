# Deployment și scalare pentru 1.000+ ferme

## Ipoteze de dimensionare

| Indicator | Valoare estimată |
|---|--:|
| Ferme | 1.000 |
| Parcele | 30.000 (30 pe fermă) |
| Utilizatori | 5.000 (5 pe fermă) |
| Conexiuni Socket.IO la vârf | 1.500 |
| Cereri API la vârf | 150-300 pe secundă |
| Senzori | 2.000, cu o citire la 10 minute |
| Citiri IoT pe an | Aproximativ 105 milioane de rânduri |

Valorile trebuie confirmate prin test de încărcare înainte de lansare.

## Arhitectura de producție

```text
Cloudflare / WAF
      │
Load balancer (TLS, WebSocket)
      │
API × 3-6 (JOBS_ENABLED=false)      Worker × 2 (JOBS_ENABLED=true)
      │                                   │
      ├────────── Redis gestionat ────────┤
      │                                   │
PgBouncer ──► PostgreSQL primar ──► replică de citire
                    │
              backup automat + PITR
```

## Configurație recomandată de pornire

| Componentă | Dimensiune | Observații |
|---|---|---|
| API | 3 instanțe, 2 vCPU, 4 GB | Scalare automată la 60% CPU |
| Worker | 2 instanțe, 2 vCPU, 4 GB | Blocările Redis evită rularea dublă |
| PostgreSQL | 8 vCPU, 32 GB, SSD 500 GB | Cu replică în altă zonă |
| PgBouncer | Mod transaction | Adaugă `pgbouncer=true` în `DATABASE_URL` |
| Redis | 4 GB, cu replică | Cache, Socket.IO, blocări |
| Stocare fișiere | S3 sau Supabase Storage | Pentru rapoarte și imagini arhivate |

Kubernetes devine util peste aproximativ 10 instanțe. Până atunci, un serviciu gestionat de containere (AWS ECS, Google Cloud Run sau Azure Container Apps) este mai simplu de operat. Cloud Run necesită configurarea sesiunilor pentru WebSocket.

## Pași de lansare

1. Construiește imaginea: `docker build -f apps/api/Dockerfile -t fermapp-api .`
2. Rulează migrările o singură dată: `npx prisma migrate deploy`.
3. Pornește instanțele API cu `JOBS_ENABLED=false`.
4. Pornește workerii cu `JOBS_ENABLED=true`.
5. Verifică `/health`, apoi mută traficul treptat.
6. Publică aplicația mobilă cu EAS Build și EAS Submit.

Secretele se păstrează într-un manager de secrete, iar `JWT_ACCESS_SECRET` se rotește periodic.

## Modificări necesare odată cu creșterea

| Prag | Modificare |
|---|---|
| Înainte de lansare | Licențe comerciale pentru serviciile meteo și satelitare |
| Înainte de lansare | Grupează parcelele apropiate pe o grilă de 0,05° pentru alerte |
| Peste 100 de ferme | Trece joburile pe BullMQ, cu reîncercări și cozi separate |
| Peste 500 de ferme | Partiționează `IotReading` pe luni sau migrează la TimescaleDB |
| Peste 500 de ferme | Partiționează `Notification` și `WeatherAlert`, cu retenție de 12 luni |
| Interogări spațiale | Adaugă PostGIS și index GiST pe geometrie |
| Peste 2.000 de ferme | Citește rapoartele din replică și mută exporturile în joburi |

## Serviciile externe la scară

La 30.000 de parcele și o verificare la 30 de minute, apelurile fără grupare ar ajunge la 1,44 milioane pe zi. Cache-ul pe o grilă de aproximativ 1 km reduce acest număr doar parțial, deci gruparea pe o grilă mai mare este obligatorie.

| Serviciu | Atenție |
|---|---|
| Open-Meteo | API-ul gratuit este pentru uz necomercial. Produsul comercial are nevoie de plan plătit |
| OpenWeatherMap | Planul se alege după numărul de apeluri rezultat din grupare |
| SoilGrids | Serviciu fără garanție de disponibilitate. Interogările se fac o dată pe parcelă, iar eșecurile se reiau automat |
| NASA POWER | Rezultatele se păstrează 7 zile în cache |
| Sentinel Hub / Copernicus | Consumul se măsoară în unități de procesare, deci descărcările trebuie programate pe fermă |
| Claude API | Sunt necesare limite lunare pe fermă și monitorizarea tokenilor |
| Expo Push | Se trimite în loturi de 100 de notificări |

## Observabilitate

- Loguri JSON centralizate în Grafana Loki, Datadog sau CloudWatch.
- Metrici pentru latență, rata de erori, conexiuni socket și durata joburilor.
- Sentry pentru erori pe server și în aplicația mobilă.
- Alerte pentru erori 5xx, joburi întârziate, replică rămasă în urmă și spațiu pe disc.

## Continuitate

| Indicator | Țintă |
|---|---|
| Disponibilitate | 99,9% |
| Pierdere maximă de date (RPO) | 5 minute |
| Timp de restaurare (RTO) | 1 oră |
| Păstrare backup | 30 de zile, cu test lunar de restaurare |

## Securitate și conformitate

- TLS peste tot și baza de date într-o rețea privată.
- Găzduire în UE pentru datele fermierilor și acorduri de prelucrare cu furnizorii.
- Export și ștergere de date la cerere, conform GDPR.
- Scanare automată a dependențelor și a imaginilor Docker în CI.
