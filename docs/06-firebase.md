# Integrare Firebase

Proiect: `fermapp-2e549`. Trecerea este treptată: `Porneste-FermApp.bat` folosește serverul actual, iar `Porneste-FermApp-Firebase.bat` pornește aplicația pe Firebase. Datele existente nu sunt încă migrate.

## Limitele configurației alese

Configurația aleasă este Expo Go și planul gratuit Spark.

| Cerință | Situație | Ce o deblochează |
|---|---|---|
| Autentificare email și parolă | Disponibilă | |
| Firestore și actualizări live | Disponibile | |
| Offline durabil | Coadă proprie în SQLite, deoarece Firestore ține datele doar în memorie în Expo Go | |
| Firebase Storage | Indisponibil. Pozele se micșorează și se salvează în colecția `photos` din Firestore | Plan Blaze |
| Cloud Functions | Indisponibile. Calculele, alertele și încheierea lucrărilor rulează pe telefoanele deschise | Plan Blaze |
| Asistent AI cu model Claude | Indisponibil, deoarece cheia nu poate fi păstrată pe telefon. Răspunsurile sunt calculate din datele parcelei | Plan Blaze |
| Imagini satelitare Sentinel | Indisponibile, deoarece parola contului nu poate fi păstrată pe telefon | Plan Blaze și cont Copernicus |
| Notificări push FCM | Indisponibile. Notificările apar cât timp aplicația este deschisă | Aplicație proprie |
| Autentificare Google și Microsoft | Indisponibile | Aplicație proprie |
| Firebase Analytics | Indisponibil în SDK-ul JavaScript pe telefon | Aplicație proprie |

## Colecții

Fiecare document are `farmId`, iar regulile de securitate compară acest câmp cu ferma utilizatorului.

| Colecție | Conținut | Scriu |
|---|---|---|
| `farms` | Fermele | Administrator |
| `invites` | Invitații, cu adresa de email ca identificator | Administrator |
| `users` | Profil, rol și fermă | Utilizatorul (nume), administrator (rol) |
| `parcels` | Parcele | Manageri |
| `cropHistory` | Culturi pe ani | Planificatori |
| `cropPlans` | Plan tehnologic, fără date financiare | Planificatori |
| `reports` | Estimări financiare, ascunse operatorilor | Planificatori |
| `recommendations` | Recomandări | Planificatori |
| `tasks` | Task-uri | Planificatori, operatorul doar statusul propriu |
| `machines` | Utilaje | Manageri, operatorul doar poziția |
| `machineWork` | Lucrări | Manageri, operatorul doar lucrările proprii |
| `irrigations` | Irigări | Manageri și operatori |
| `expenses` | Cheltuieli, ascunse operatorilor | Planificatori |
| `soilAnalysis`, `weatherData` | Date de sol și meteo | Toată echipa |
| `satelliteImages` | Observații satelitare | Planificatori |
| `notifications` | Notificări, vizibile doar destinatarului | Toată echipa |
| `photos`, `observations` | Fotografii și observații din teren | Autorul, managerii pot șterge |

Firestore nu acceptă liste în liste. Conturul parcelei se salvează în `coordinates` ca listă de puncte, iar poligonul GeoJSON complet în `geometry`, ca text.

## Activare

1. În consola Firebase, activează **Authentication** cu Email/Password și creează baza **Firestore**.
2. Copiază cheia `apiKey` din setările proiectului în `apps/mobile/.env`, la `EXPO_PUBLIC_FIREBASE_API_KEY`.
3. Publică regulile: `npx firebase-tools deploy --only firestore`.

## Testarea regulilor

Testele au nevoie de Java 21 sau mai nou:

```bash
npx firebase-tools emulators:exec --only auth,firestore --project demo-fermapp "npm run test:rules -w @fermapp/firebase"
```
