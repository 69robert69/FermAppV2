# Endpointuri API

Fișier generat cu `npm run docs:api`. Baza este `/api/v1`. Sunt 105 endpointuri.
Documentația interactivă Swagger rulează la `/api/docs`.

## Asistent AI

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| POST | `/ai/chat` | Trimite o întrebare asistentului agronomic | Toate rolurile |
| GET | `/ai/conversations` | Conversațiile utilizatorului | Toate rolurile |
| GET | `/ai/conversations/:id` | Mesajele unei conversații | Toate rolurile |

## Autentificare

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| POST | `/auth/register` | Creează un cont și o fermă nouă | Public |
| POST | `/auth/login` | Autentificare cu email și parolă | Public |
| POST | `/auth/refresh` | Reînnoiește sesiunea și rotește tokenul de refresh | Public |
| POST | `/auth/logout` | Închide sesiunea curentă | Toate rolurile |
| GET | `/auth/me` | Profilul utilizatorului curent | Toate rolurile |
| POST | `/auth/switch-farm` | Schimbă ferma activă | Toate rolurile |
| POST | `/auth/push-token` | Înregistrează dispozitivul pentru notificări push | Toate rolurile |

## Costuri

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| GET | `/costs` | Listează costurile | Admin, Manager, Agronom |
| POST | `/costs` | Adaugă un cost | Admin, Manager |
| PATCH | `/costs/:id` | Modifică un cost | Admin, Manager |
| DELETE | `/costs/:id` | Șterge un cost | Admin, Manager |
| GET | `/parcels/:id/costs/summary` | Cost pe hectar, cost total și profit estimat sau real | Admin, Manager, Agronom |

## Culturi și rotație

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| GET | `/crops` | Catalogul culturilor predefinite | Toate rolurile |
| GET | `/parcels/:id/seasons` | Istoricul culturilor pe ultimii 10 ani | Toate rolurile |
| POST | `/parcels/:id/seasons` | Adaugă o cultură în istoricul parcelei | Admin, Manager, Agronom |
| PATCH | `/seasons/:id` | Modifică o înregistrare de cultură | Admin, Manager, Agronom |
| DELETE | `/seasons/:id` | Șterge o înregistrare de cultură | Admin, Manager, Agronom |
| POST | `/parcels/:id/rotation/evaluate` | Evaluează cultura propusă | Toate rolurile |
| GET | `/parcels/:id/rotation/options` | Ordonează toate culturile după scorul de rotație | Toate rolurile |
| GET | `/parcels/:id/recommendations` | Recomandă culturi după sol, climă și rotație | Toate rolurile |
| GET | `/parcels/:id/climate` | Compară anii agricoli pe baza datelor NASA POWER | Toate rolurile |
| GET | `/parcels/:id/development` | Estimează dezvoltarea culturii curente prin grade-zile (GDD) | Toate rolurile |

## Dashboard

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| GET | `/dashboard` | Indicatorii principali ai fermei | Toate rolurile |
| GET | `/sync/changes` | Modificările de după ultima sincronizare offline | Toate rolurile |

## Parcel Intelligence

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| GET | `/parcels/:id/intelligence` | Toate datele panoului Parcel Intelligence | Toate rolurile |
| GET | `/parcels/:id/weather` | Vremea de azi, prognoza pe 7 și 14 zile și alertele de stropire | Toate rolurile |
| GET | `/parcels/:id/rotation-score` | Scorul de rotație și istoricul culturilor | Toate rolurile |
| GET | `/parcels/:id/economics` | Costuri, venit estimat, profit, marjă și ROI | Admin, Manager, Agronom |
| POST | `/parcels/:id/incomes` | Înregistrează un venit realizat | Admin, Manager |
| GET | `/parcels/:id/ndvi` | NDVI, NDRE și starea vegetației: acum, acum 7 și 30 de zile | Toate rolurile |
| GET | `/parcels/:id/tasks` | Task-urile parcelei și sumarul pe status | Toate rolurile |
| GET | `/parcels/:id/observations` | Observațiile din teren | Toate rolurile |
| POST | `/parcels/:id/observations` | Adaugă o observație din teren | Toate rolurile |
| GET | `/parcels/:id/photos` | Galeria foto a parcelei | Toate rolurile |
| POST | `/parcels/:id/photos` | Încarcă o fotografie cu note | Toate rolurile |
| GET | `/parcels/:id/photos/:photoId/file` | Conținutul fotografiei | Toate rolurile |
| DELETE | `/parcels/:id/photos/:photoId` | Șterge o fotografie | Toate rolurile |
| GET | `/parcels/:id/report` | Raport PDF pentru parcelă | Toate rolurile |

## IoT

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| GET | `/iot/devices` | Listează dispozitivele IoT | Toate rolurile |
| POST | `/iot/devices` | Înregistrează un dispozitiv. Cheia se afișează o singură dată. | Admin, Manager |
| DELETE | `/iot/devices/:id` | Șterge un dispozitiv și citirile lui | Admin, Manager |
| GET | `/iot/devices/:id/readings` | Ultimele citiri ale unui dispozitiv | Toate rolurile |
| POST | `/iot/ingest` | Primește citiri de la senzori, cu antetul X-Device-Key | Public |

## Irigații

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| POST | `/irrigation/estimate` | Calculează durata și ETA irigării | Toate rolurile |
| GET | `/irrigation` | Listează irigările | Toate rolurile |
| POST | `/irrigation` | Pornește o irigare | Admin, Manager, Operator |
| GET | `/irrigation/:id` | Detaliile și progresul irigării | Toate rolurile |
| POST | `/irrigation/:id/pause` | Pune irigarea pe pauză | Admin, Manager, Operator |
| POST | `/irrigation/:id/resume` | Reia irigarea | Admin, Manager, Operator |
| POST | `/irrigation/:id/complete` | Finalizează irigarea | Admin, Manager, Operator |
| POST | `/irrigation/:id/cancel` | Anulează irigarea | Admin, Manager, Operator |
| GET | `/parcels/:id/water` | Necesarul de apă, precipitațiile primite și deficitul | Toate rolurile |

## Utilaje

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| GET | `/machines` | Listează utilajele | Toate rolurile |
| POST | `/machines` | Adaugă un utilaj | Admin, Manager |
| PATCH | `/machines/:id` | Modifică un utilaj | Admin, Manager |
| DELETE | `/machines/:id` | Arhivează un utilaj | Admin, Manager |

## Hartă

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| GET | `/map/overview` | Situația live a tuturor parcelelor, pentru afișarea pe hartă | Toate rolurile |

## Notificări

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| GET | `/notifications` | Ultimele notificări ale utilizatorului | Toate rolurile |
| POST | `/notifications/:id/read` | Marchează notificarea drept citită | Toate rolurile |

## Parcele

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| GET | `/parcels` | Listează parcelele fermei | Toate rolurile |
| POST | `/parcels` | Creează o parcelă și pornește analiza solului | Admin, Manager |
| POST | `/parcels/import` | Importă parcele din GeoJSON, KML, KMZ, SHP sau APIA LPIS | Admin, Manager |
| GET | `/parcels/export` | Exportă parcelele active | Toate rolurile |
| GET | `/parcels/:id` | Detaliile unei parcele | Toate rolurile |
| PATCH | `/parcels/:id` | Modifică datele sau conturul parcelei | Admin, Manager |
| POST | `/parcels/:id/archive` | Arhivează parcela | Admin, Manager |
| POST | `/parcels/:id/restore` | Restaurează parcela din arhivă | Admin, Manager |

## Plan tehnologic

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| POST | `/parcels/wizard/analyze` | Analizează parcela și generează planul fără să salveze nimic | Admin, Manager, Agronom |
| POST | `/parcels/wizard/complete` | Salvează parcela, culturile, planul tehnologic și task-urile | Admin, Manager, Agronom |
| GET | `/parcels/:id/plan` | Planul activ și rapoartele salvate ale parcelei | Toate rolurile |

## Rapoarte

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| GET | `/reports/:type` | Generează un raport în JSON, PDF sau Excel | Toate rolurile |

## Satelit

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| GET | `/parcels/:id/satellite` | Istoricul NDVI, NDRE și Health Index | Toate rolurile |
| POST | `/parcels/:id/satellite/refresh` | Descarcă observațiile Sentinel-2 pentru perioada indicată | Admin, Manager, Agronom |
| GET | `/parcels/:id/satellite/compare` | Compară două date de observație | Toate rolurile |
| GET | `/parcels/:id/satellite/image` | Imaginea colorată a indicelui pentru o dată | Toate rolurile |

## Sol

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| GET | `/parcels/:id/soil` | Profilul de sol al parcelei | Toate rolurile |
| POST | `/parcels/:id/soil/refresh` | Reinterogează SoilGrids | Admin, Manager, Agronom |

## To Do

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| GET | `/tasks/types` | Task-urile predefinite | Toate rolurile |
| GET | `/tasks` | Listează task-urile | Toate rolurile |
| POST | `/tasks` | Creează un task | Admin, Manager, Agronom |
| PATCH | `/tasks/:id` | Modifică un task | Admin, Manager, Agronom |
| POST | `/tasks/:id/status` | Schimbă statusul. Operatorii pot schimba doar task-urile proprii. | Toate rolurile |
| DELETE | `/tasks/:id` | Șterge un task | Admin, Manager, Agronom |

## Echipă

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| GET | `/team` | Listează membrii fermei | Toate rolurile |
| POST | `/team` | Adaugă un membru. Parola este necesară doar pentru conturile noi. | Admin |
| PATCH | `/team/:userId` | Schimbă rolul unui membru | Admin |
| DELETE | `/team/:userId` | Elimină un membru din fermă | Admin |

## Meteo

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| GET | `/parcels/:id/weather/forecast` | Prognoză pentru 24 de ore, 7 sau 14 zile | Toate rolurile |
| GET | `/parcels/:id/weather/history` | Istoric meteo pentru 30 de zile sau 12 luni | Toate rolurile |
| GET | `/parcels/:id/weather/windows` | Ferestre optime pentru lucrări, pe următoarele 7 zile | Toate rolurile |
| GET | `/alerts` | Alertele meteo ale fermei | Toate rolurile |
| POST | `/parcels/:id/alerts/check` | Verifică acum alertele meteo pentru parcelă | Toate rolurile |

## Work

| Metodă | Cale | Descriere | Acces |
|---|---|---|---|
| POST | `/work/estimate` | Calculează capacitatea, durata și ETA | Toate rolurile |
| GET | `/work` | Listează lucrările | Toate rolurile |
| POST | `/work` | Pornește o lucrare | Admin, Manager, Operator |
| GET | `/work/:id` | Detaliile și progresul unei lucrări | Toate rolurile |
| POST | `/work/:id/location` | Trimite poziția GPS a utilajului | Admin, Manager, Operator |
| GET | `/work/:id/location` | Ultima poziție GPS a utilajului | Toate rolurile |
| POST | `/work/:id/pause` | Pune lucrarea pe pauză | Admin, Manager, Operator |
| POST | `/work/:id/resume` | Reia lucrarea | Admin, Manager, Operator |
| POST | `/work/:id/complete` | Finalizează lucrarea | Admin, Manager, Operator |
| POST | `/work/:id/cancel` | Anulează lucrarea | Admin, Manager, Operator |
