# Implementation plan and estimates

The estimates are indicative and assume a team experienced in React Native, Node.js and GIS. One month means 20 working days.

## Complexity per module, if the project started from scratch

| Module | Complexity | Person-days |
|---|---|--:|
| Foundation: authentication, roles, errors, Swagger | Medium | 20 |
| 1. Parcels, drawing, GIS import and export | High | 35 |
| 2. Crop history | Low | 12 |
| 3. Rotation engine | Medium | 10 |
| 4. Machines | Low | 6 |
| 5. Real-time Work | Medium | 18 |
| 6. To Do and notifications | Low | 10 |
| 7. Irrigation | Medium | 12 |
| 8. Open-Meteo | Low | 10 |
| 9. OpenWeatherMap and alerts | Medium | 10 |
| 10. Soil analysis | Low | 8 |
| 11. Crop recommendations | Medium | 10 |
| 12. NASA POWER and GDD | Low | 8 |
| 13. Optimal work windows | Medium | 8 |
| 14. Water requirement | Low | 6 |
| 15. Satellite and NDVI | High | 25 |
| 16. Costs | Low | 8 |
| 17. PDF and Excel reports | Medium | 14 |
| 18. AI assistant | Medium | 15 |
| 19. IoT | Medium | 15 |
| Offline and sync | High | 25 |
| Design and interface | Medium | 30 |
| Testing and stabilization | High | 40 |
| Infrastructure and launch | Medium | 20 |
| **Total** | | **375** |

That is about 19 person-months. A team of 5 people would need 5-6 months.

## What remains before production

The code in this repository covers all modules. The work below remains before launch.

| Stage | Activities | Person-days |
|---|---|--:|
| 1. Device validation | Android and iOS builds, tests on real phones, maps, GPS, push, offline | 15 |
| 2. External services | Accounts and real tests for Sentinel, OpenWeatherMap, Claude API and Expo Push | 10 |
| 3. APIA data | Tests with real LPIS files and field adjustments | 5 |
| 4. Agronomic validation | Review of the rotation rules, thresholds and soil thresholds by an agronomist | 8 |
| 5. Security | Audit, penetration test, GDPR, privacy policy | 10 |
| 6. Infrastructure | Production environment, backup, monitoring, alerts, CI/CD | 12 |
| 7. Load test | Simulation for 1,000 farms and optimizations | 8 |
| 8. Pilot | 5-10 farms for one working season, with fixes | 25 |
| 9. Launch | Google Play, App Store, user documentation | 8 |
| **Total** | | **101** |

A team of 3 people can complete these stages in about 8-10 weeks, not counting the calendar duration of the pilot.

## Recommended order

1. Validate the core flows on real phones: parcels, Work, To Do and Irrigation.
2. Enable the external services, in order of value to the farmer: alerts, satellite, AI.
3. Run the pilot before the spring season, when field work is most intense.
4. Add IoT hardware integrations only after the pilot confirms the farmers' requirements.

## Main risks

| Risk | Impact | Mitigation |
|---|---|---|
| Weather and satellite service licences | Costs or blocked access | Commercial contracts before launch |
| LPIS format different from the one assumed | Incomplete import | Tests with real APIA files |
| Poor coverage in the field | Outdated data | Offline testing in the field |
| General agronomic rules | Recommendations unsuited to local conditions | Validation with agronomists from each area |
| Uncontrolled AI costs | Budget overrun | Per-farm limits and usage monitoring |
