# Firebase integration

Project: `fermapp-2e549`. The transition is gradual: `Start-FermApp.bat` uses the current server, and `Start-FermApp-Firebase.bat` starts the app on Firebase. Existing data is not migrated yet.

## Limits of the chosen configuration

The chosen configuration is Expo Go and the free Spark plan.

| Requirement | Status | What unlocks it |
|---|---|---|
| Email and password authentication | Available | |
| Firestore and live updates | Available | |
| Durable offline | Own queue in SQLite, because Firestore keeps data only in memory in Expo Go | |
| Firebase Storage | Unavailable. Photos are shrunk and saved in the Firestore `photos` collection | Blaze plan |
| Cloud Functions | Unavailable. Calculations, alerts and completing operations run on open phones | Blaze plan |
| AI assistant with a Claude model | Unavailable, because the key cannot be kept on the phone. Answers are calculated from the parcel data | Blaze plan |
| Sentinel satellite imagery | Unavailable, because the account password cannot be kept on the phone | Blaze plan and Copernicus account |
| FCM push notifications | Unavailable. Notifications appear while the app is open | Own app build |
| Google and Microsoft sign-in | Unavailable | Own app build |
| Firebase Analytics | Unavailable in the JavaScript SDK on the phone | Own app build |

## Collections

Every document has a `farmId`, and the security rules compare this field with the user's farm.

| Collection | Content | Written by |
|---|---|---|
| `farms` | Farms | Administrator |
| `invites` | Invitations, with the email address as the identifier | Administrator |
| `users` | Profile, role and farm | The user (name), administrator (role) |
| `parcels` | Parcels | Managers |
| `cropHistory` | Crops by year | Planners |
| `cropPlans` | Technology plan, without financial data | Planners |
| `reports` | Financial estimates, hidden from operators | Planners |
| `recommendations` | Recommendations | Planners |
| `tasks` | Tasks | Planners, the operator only their own status |
| `machines` | Machines | Managers, the operator only the position |
| `machineWork` | Field operations | Managers, the operator only their own operations |
| `irrigations` | Irrigations | Managers and operators |
| `expenses` | Expenses, hidden from operators | Planners |
| `soilAnalysis`, `weatherData` | Soil and weather data | The whole team |
| `satelliteImages` | Satellite observations | Planners |
| `notifications` | Notifications, visible only to the recipient | The whole team |
| `photos`, `observations` | Field photos and observations | The author, managers can delete |

Firestore does not accept nested lists. The parcel outline is saved in `coordinates` as a list of points, and the full GeoJSON polygon in `geometry`, as text.

## Activation

1. In the Firebase console, enable **Authentication** with Email/Password and create the **Firestore** database.
2. Copy the `apiKey` from the project settings into `apps/mobile/.env`, as `EXPO_PUBLIC_FIREBASE_API_KEY`.
3. Publish the rules: `npx firebase-tools deploy --only firestore`.

## Testing the rules

The tests need Java 21 or newer:

```bash
npx firebase-tools emulators:exec --only auth,firestore --project demo-fermapp "npm run test:rules -w @fermapp/firebase"
```
