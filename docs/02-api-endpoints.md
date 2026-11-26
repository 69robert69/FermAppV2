# API endpoints

File generated with `npm run docs:api`. The base path is `/api/v1`. There are 112 endpoints.
Interactive Swagger documentation runs at `/api/docs`.

## AI Assistant

| Method | Path | Description | Access |
|---|---|---|---|
| POST | `/ai/chat` | Send a question to the agronomy assistant | All roles |
| GET | `/ai/conversations` | The user’s conversations | All roles |
| GET | `/ai/conversations/:id` | Messages of a conversation | All roles |

## Authentication

| Method | Path | Description | Access |
|---|---|---|---|
| POST | `/auth/register` | Create an account and a new farm | Public |
| POST | `/auth/login` | Sign in with email and password | Public |
| POST | `/auth/refresh` | Renew the session and rotate the refresh token | Public |
| POST | `/auth/logout` | Close the current session | All roles |
| GET | `/auth/me` | Current user profile | All roles |
| POST | `/auth/switch-farm` | Switch the active farm | All roles |
| POST | `/auth/push-token` | Register the device for push notifications | All roles |

## Costs

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/costs` | List costs | Admin, Manager, Agronomist |
| POST | `/costs` | Add a cost | Admin, Manager |
| PATCH | `/costs/:id` | Update a cost | Admin, Manager |
| DELETE | `/costs/:id` | Delete a cost | Admin, Manager |
| GET | `/parcels/:id/costs/summary` | Cost per hectare, total cost and estimated or actual profit | Admin, Manager, Agronomist |

## Crops and rotation

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/crops` | Catalog of predefined crops | All roles |
| GET | `/parcels/:id/seasons` | Crop history for the last 10 years | All roles |
| POST | `/parcels/:id/seasons` | Add a crop to the parcel history | Admin, Manager, Agronomist |
| PATCH | `/seasons/:id` | Update a crop record | Admin, Manager, Agronomist |
| DELETE | `/seasons/:id` | Delete a crop record | Admin, Manager, Agronomist |
| POST | `/parcels/:id/rotation/evaluate` | Evaluate the proposed crop | All roles |
| GET | `/parcels/:id/rotation/options` | Rank all crops by rotation score | All roles |
| GET | `/parcels/:id/recommendations` | Recommend crops by soil, climate and rotation | All roles |
| GET | `/parcels/:id/climate` | Compare agricultural years based on NASA POWER data | All roles |
| GET | `/parcels/:id/development` | Estimate the development of the current crop using growing degree-days (GDD) | All roles |

## Dashboard

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/dashboard` | Main farm indicators | All roles |
| GET | `/sync/changes` | Changes since the last offline sync | All roles |

## Economics

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/economics/settings` | Fuel price, labour rates and machine ownership costs | Admin, Manager, Agronomist |
| POST | `/economics/settings` | Save the economic settings of the farm | Admin, Manager |
| GET | `/economics/sheets` | The economic sheets of every parcel in a season | Admin, Manager, Agronomist |
| GET | `/economics/sheets/:parcelId/:year` | The economic sheet of a parcel in a season | Admin, Manager, Agronomist |
| POST | `/economics/sheets/:parcelId/:year` | Save the seed, fertiliser, crop protection, other costs and revenue of a parcel | Admin, Manager, Agronomist |
| GET | `/economics/farm` | Real cost per hectare and profit of every parcel, by crop and by technology | Admin, Manager, Agronomist |

## Parcel Intelligence

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/parcels/:id/intelligence` | All data for the Parcel Intelligence panel | All roles |
| GET | `/parcels/:id/weather` | Today’s weather, 7- and 14-day forecast and spraying alerts | All roles |
| GET | `/parcels/:id/rotation-score` | Rotation score and crop history | All roles |
| GET | `/parcels/:id/economics` | Costs, estimated revenue, profit, margin and ROI | Admin, Manager, Agronomist |
| POST | `/parcels/:id/incomes` | Record realized revenue | Admin, Manager |
| GET | `/parcels/:id/ndvi` | NDVI, NDRE and vegetation status: now, 7 and 30 days ago | All roles |
| GET | `/parcels/:id/tasks` | Parcel tasks and status summary | All roles |
| GET | `/parcels/:id/observations` | Field observations | All roles |
| POST | `/parcels/:id/observations` | Add a field observation | All roles |
| GET | `/parcels/:id/photos` | Parcel photo gallery | All roles |
| POST | `/parcels/:id/photos` | Upload a photo with notes | All roles |
| GET | `/parcels/:id/photos/:photoId/file` | Photo content | All roles |
| DELETE | `/parcels/:id/photos/:photoId` | Delete a photo | All roles |
| GET | `/parcels/:id/report` | PDF report for the parcel | All roles |

## IoT

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/iot/devices` | List IoT devices | All roles |
| POST | `/iot/devices` | Register a device. The key is shown only once. | Admin, Manager |
| DELETE | `/iot/devices/:id` | Delete a device and its readings | Admin, Manager |
| GET | `/iot/devices/:id/readings` | Latest readings of a device | All roles |
| POST | `/iot/ingest` | Receive sensor readings, with the X-Device-Key header | Public |

## Irrigation

| Method | Path | Description | Access |
|---|---|---|---|
| POST | `/irrigation/estimate` | Calculate irrigation duration and ETA | All roles |
| GET | `/irrigation` | List irrigations | All roles |
| POST | `/irrigation` | Start an irrigation | Admin, Manager, Operator |
| GET | `/irrigation/:id` | Irrigation details and progress | All roles |
| POST | `/irrigation/:id/pause` | Pause the irrigation | Admin, Manager, Operator |
| POST | `/irrigation/:id/resume` | Resume the irrigation | Admin, Manager, Operator |
| POST | `/irrigation/:id/complete` | Complete the irrigation | Admin, Manager, Operator |
| POST | `/irrigation/:id/cancel` | Cancel the irrigation | Admin, Manager, Operator |
| GET | `/parcels/:id/water` | Water requirement, rainfall received and deficit | All roles |

## Machines

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/machines` | List machines | All roles |
| POST | `/machines` | Add a machine | Admin, Manager |
| PATCH | `/machines/:id` | Update a machine | Admin, Manager |
| DELETE | `/machines/:id` | Archive a machine | Admin, Manager |

## Map

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/map/overview` | Live status of all parcels, for display on the map | All roles |

## Notifications

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/notifications` | Latest notifications of the user | All roles |
| POST | `/notifications/:id/read` | Mark the notification as read | All roles |

## Parcels

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/parcels` | List the farm’s parcels | All roles |
| POST | `/parcels` | Create a parcel and start the soil analysis | Admin, Manager |
| POST | `/parcels/import` | Import parcels from GeoJSON, KML, KMZ, SHP or APIA LPIS | Admin, Manager |
| GET | `/parcels/export` | Export active parcels | All roles |
| GET | `/parcels/:id` | Parcel details | All roles |
| PATCH | `/parcels/:id` | Update the parcel data or outline | Admin, Manager |
| POST | `/parcels/:id/tillage` | Set the tillage technology of a year; the current one follows the history | Admin, Manager |
| POST | `/parcels/:id/archive` | Archive the parcel | Admin, Manager |
| POST | `/parcels/:id/restore` | Restore the parcel from the archive | Admin, Manager |

## Technology plan

| Method | Path | Description | Access |
|---|---|---|---|
| POST | `/parcels/wizard/analyze` | Analyse the parcel and generate the plan without saving anything | Admin, Manager, Agronomist |
| POST | `/parcels/wizard/complete` | Save the parcel, crops, technology plan and tasks | Admin, Manager, Agronomist |
| GET | `/parcels/:id/plan` | The active plan and saved reports of the parcel | All roles |

## Reports

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/reports/:type` | Generate a report in JSON, PDF or Excel | All roles |

## Satellite

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/parcels/:id/satellite` | NDVI, NDRE and Health Index history | All roles |
| POST | `/parcels/:id/satellite/refresh` | Download Sentinel-2 observations for the given period | Admin, Manager, Agronomist |
| GET | `/parcels/:id/satellite/compare` | Compare two observation dates | All roles |
| GET | `/parcels/:id/satellite/image` | Coloured index image for a date | All roles |

## Soil

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/parcels/:id/soil` | Soil profile of the parcel | All roles |
| POST | `/parcels/:id/soil/refresh` | Query SoilGrids again | Admin, Manager, Agronomist |

## To Do

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/tasks/types` | Predefined tasks | All roles |
| GET | `/tasks` | List tasks | All roles |
| POST | `/tasks` | Create a task | Admin, Manager, Agronomist |
| PATCH | `/tasks/:id` | Update a task | Admin, Manager, Agronomist |
| POST | `/tasks/:id/status` | Change the status. Operators can only change their own tasks. | All roles |
| DELETE | `/tasks/:id` | Delete a task | Admin, Manager, Agronomist |

## Team

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/team` | List farm members | All roles |
| POST | `/team` | Add a member. A password is required only for new accounts. | Admin |
| PATCH | `/team/:userId` | Change a member’s role | Admin |
| DELETE | `/team/:userId` | Remove a member from the farm | Admin |

## Weather

| Method | Path | Description | Access |
|---|---|---|---|
| GET | `/parcels/:id/weather/forecast` | Forecast for 24 hours, 7 or 14 days | All roles |
| GET | `/parcels/:id/weather/history` | Weather history for 30 days or 12 months | All roles |
| GET | `/parcels/:id/weather/windows` | Optimal windows for field work over the next 7 days | All roles |
| GET | `/alerts` | Farm weather alerts | All roles |
| POST | `/parcels/:id/alerts/check` | Check weather alerts for the parcel now | All roles |

## Work

| Method | Path | Description | Access |
|---|---|---|---|
| POST | `/work/estimate` | Calculate capacity, duration and ETA | All roles |
| GET | `/work` | List operations | All roles |
| POST | `/work` | Start an operation | Admin, Manager, Operator |
| GET | `/work/:id` | Details and progress of an operation | All roles |
| POST | `/work/:id/location` | Send the machine’s GPS position | Admin, Manager, Operator |
| GET | `/work/:id/location` | Latest GPS position of the machine | All roles |
| POST | `/work/:id/pause` | Pause the operation | Admin, Manager, Operator |
| POST | `/work/:id/resume` | Resume the operation | Admin, Manager, Operator |
| POST | `/work/:id/complete` | Complete the operation | Admin, Manager, Operator |
| POST | `/work/:id/cancel` | Cancel the operation | Admin, Manager, Operator |
