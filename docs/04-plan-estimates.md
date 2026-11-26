# Plan de implementare și estimări

Estimările sunt orientative și presupun o echipă cu experiență în React Native, Node.js și GIS. O lună înseamnă 20 de zile lucrătoare.

## Complexitate pe module, dacă proiectul ar începe de la zero

| Modul | Complexitate | Zile-om |
|---|---|--:|
| Fundație: autentificare, roluri, erori, Swagger | Medie | 20 |
| 1. Parcele, desenare, import și export GIS | Ridicată | 35 |
| 2. Istoric culturi | Scăzută | 12 |
| 3. Motor de rotație | Medie | 10 |
| 4. Utilaje | Scăzută | 6 |
| 5. Work în timp real | Medie | 18 |
| 6. To Do și notificări | Scăzută | 10 |
| 7. Irigații | Medie | 12 |
| 8. Open-Meteo | Scăzută | 10 |
| 9. OpenWeatherMap și alerte | Medie | 10 |
| 10. Analiza solului | Scăzută | 8 |
| 11. Recomandări culturi | Medie | 10 |
| 12. NASA POWER și GDD | Scăzută | 8 |
| 13. Ferestre optime de lucru | Medie | 8 |
| 14. Necesar de apă | Scăzută | 6 |
| 15. Satelit și NDVI | Ridicată | 25 |
| 16. Costuri | Scăzută | 8 |
| 17. Rapoarte PDF și Excel | Medie | 14 |
| 18. Asistent AI | Medie | 15 |
| 19. IoT | Medie | 15 |
| Offline și sincronizare | Ridicată | 25 |
| Design și interfață | Medie | 30 |
| Testare și stabilizare | Ridicată | 40 |
| Infrastructură și lansare | Medie | 20 |
| **Total** | | **375** |

Asta înseamnă aproximativ 19 luni-om. O echipă de 5 persoane ar avea nevoie de 5-6 luni.

## Ce rămâne până la producție

Codul din acest repository acoperă toate modulele. Lucrările de mai jos rămân înainte de lansare.

| Etapă | Activități | Zile-om |
|---|---|--:|
| 1. Validare pe dispozitive | Build Android și iOS, test pe telefoane reale, hărți, GPS, push, offline | 15 |
| 2. Servicii externe | Conturi și teste reale pentru Sentinel, OpenWeatherMap, Claude API și Expo Push | 10 |
| 3. Date APIA | Test cu fișiere LPIS reale și ajustarea câmpurilor | 5 |
| 4. Validare agronomică | Revizuirea regulilor de rotație, a pragurilor și a pragurilor de sol de către un agronom | 8 |
| 5. Securitate | Audit, test de penetrare, GDPR, politică de confidențialitate | 10 |
| 6. Infrastructură | Mediu de producție, backup, monitorizare, alerte, CI/CD | 12 |
| 7. Test de încărcare | Simulare pentru 1.000 de ferme și optimizări | 8 |
| 8. Pilot | 5-10 ferme timp de un sezon de lucru, cu remedieri | 25 |
| 9. Lansare | Google Play, App Store, documentație pentru utilizatori | 8 |
| **Total** | | **101** |

O echipă de 3 persoane poate finaliza aceste etape în aproximativ 8-10 săptămâni, fără durata calendaristică a pilotului.

## Ordinea recomandată

1. Validează pe telefoane reale fluxurile de bază: parcele, Work, To Do și Irigații.
2. Activează serviciile externe, în ordinea valorii pentru fermier: alerte, satelit, AI.
3. Rulează pilotul înainte de sezonul de primăvară, când lucrările sunt cele mai intense.
4. Adaugă integrările hardware IoT doar după ce pilotul confirmă cerințele fermierilor.

## Riscuri principale

| Risc | Impact | Măsură |
|---|---|---|
| Licențele serviciilor meteo și satelitare | Costuri sau blocarea accesului | Contracte comerciale înainte de lansare |
| Format LPIS diferit de cel presupus | Import incomplet | Test cu fișiere APIA reale |
| Acoperire slabă în câmp | Date neactualizate | Testare offline în teren |
| Reguli agronomice generale | Recomandări nepotrivite local | Validare cu agronomi din fiecare zonă |
| Costuri AI necontrolate | Buget depășit | Limite pe fermă și monitorizarea consumului |
