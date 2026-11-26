# Flows and wireframes

## App flows

### Creating a parcel

```text
Map → New parcel → tap the corners → the area is calculated live
    → fill in the name → Save
    → the API validates the outline and saves the parcel
    → SoilGrids runs in the background → the soil profile appears in the Soil tab
```

### Rotation evaluation

```text
Parcel → Rotation → the API reads the history → the engine calculates the 0-100 score
       → ✅ Recommended (70+) / ⚠️ Acceptable (40-69) / ❌ Not recommended (below 40)
       → the explanation shows the main reason
```

### Live field work

```text
Work → parcel, machine, width, speed, efficiency
     → Capacity = Width × Speed × Efficiency / 10
     → Start → Socket.IO notifies the team → the timer runs on the phone
     → Pause / Resume / Complete, or automatic completion at 100%
     → diesel consumption calculated → push notification
```

### Irrigation

```text
Irrigation → parcel, system, route, speed → duration = route / speed
           → Start → live timer and percent done
           → at the end: status updated and push notification
           → the applied dose goes into the water balance
```

### Weather alerts

```text
Every 30 minutes: forecast for each parcel → alert rules
→ new alert? → save + Socket.IO + push
→ the same alert is not repeated on the same day
```

### Offline sync

```text
No signal: reads come from SQLite, changes go into the queue
Signal returns: the queue is sent in order, with an idempotency key
  2xx → operation deleted
  4xx → operation flagged and shown in Settings
  network or 5xx → retried later
```

### AI assistant

```text
Question + parcel → the server gathers history, soil, NDVI, field work, rotation, forecast
→ Claude answers based on this data
→ without a key or on error: a summary calculated from the same data
```

## Wireframes

### Dashboard

```text
┌──────────────────────────────┐
│ Demo Farm                [⚙] │
│ ┌──────────────────────────┐ │
│ │ ▱ ▰ ▱ ▰ ▱   outlines     │ │
│ │ 12 parcels   270.4 ha    │ │
│ │ 133.5 ha     136.9 ha    │ │
│ │ cultivated   free        │ │
│ └──────────────────────────┘ │
│ Weather alerts               │
│ ⛈ Rain in 3 hours, South-1   │
│ In progress                  │
│ Sowing, North 3         42%  │
│ ▓▓▓▓▓▓░░░░  finishes 16:40   │
│ Crops in 2026                │
│ Wheat   ▓▓▓▓▓▓░░   84 ha     │
├──────────────────────────────┤
│ ▦   ◫   ⚙   ☑   💧  ▤   ✦   │
└──────────────────────────────┘
```

### Map

```text
┌──────────────────────────────┐
│                         [◎]  │
│    ╱▔▔▔▔▔╲  green       [⇅]  │
│   ▕ North ▏                  │
│    ╲_____╱    ╱▔▔▔▔▔╲ yellow │
│              ▕ South ▏       │
│               ╲_____╱        │
│ ┌──────────────────────────┐ │
│ │ ▱ North 3, 68 ha, Wheat  │ │
│ │ NDVI 0.72                │ │
│ │ [ Open parcel ]          │ │
│ └──────────────────────────┘ │
└──────────────────────────────┘
```

### Parcel editor

```text
┌──────────────────────────────┐
│ ← Parcel                     │
│  ●───────●              [↶]  │
│  │  map   ╲             [🗑]  │
│  ●─────────●                 │
├──────────────────────────────┤
│ 68.42 ha      3,412 m        │
│ [Parcel name             ]   │
│ [Code] [Locality] [County]   │
│ [ Save parcel ]              │
└──────────────────────────────┘
```

### Parcel details

```text
┌──────────────────────────────┐
│ ← North 3                [✎] │
│ ▱  68.42 ha, NV-003          │
│ (Crops)(Rotation)(Soil)(Wea… │
│ ┌──────────────────────────┐ │
│ │ Sunflower             22 │ │
│ │ ❌ Not recommended       │ │
│ │ It was grown 2 years     │ │
│ │ ago. Minimum break 4 yrs.│ │
│ └──────────────────────────┘ │
│ ┌──────────────────────────┐ │
│ │ Soybean               90 │ │
│ │ ✅ Recommended           │ │
│ └──────────────────────────┘ │
└──────────────────────────────┘
```

### Work

```text
┌──────────────────────────────┐
│ Work                    [🚜] │
│ Sowing, North 3         42%  │
│ ▓▓▓▓▓▓░░░░░                  │
│ Elapsed 02:14:08  ETA 16:40  │
│ [ Pause ]  [ Complete ]      │
│ Start an operation           │
│ (North 3)(South-1)           │
│ (Ploughing)(Discing)(Sowing) │
│ [6 m] [8 km/h] [80 %]        │
│ 3.84 ha/hour  17.8 hours     │
│ [ Start operation ]          │
└──────────────────────────────┘
```

### To Do

```text
┌──────────────────────────────┐
│ To Do                        │
│ (To Do)(In Progress)(Done)   │
│ ┃ Herbicide appl.     Start  │
│ ┃ South-1, Ana, High         │
│ ┃ Due: 20 Nov                │
│ Add a task                   │
│ (Fertilization)(Herbicide)   │
│ [Deadline] (Parcel)(Assignee)│
│ [ Save task ]                │
└──────────────────────────────┘
```

### Irrigation

```text
┌──────────────────────────────┐
│ Irrigation                   │
│ Lunca Mare              61%  │
│ Hose reel, 400 m at 25 m/hour│
│ ▓▓▓▓▓▓▓░░░░                  │
│ Elapsed 09:45:12  ETA 22:00  │
│ [ Pause ]  [ Complete ]      │
│ Start an irrigation          │
│ (Hose reel)(Pivot)(Linear)   │
│ [Route m] [Speed m/hour]     │
│ [ Start irrigation ]         │
└──────────────────────────────┘
```

### Reports

```text
┌──────────────────────────────┐
│ Reports                      │
│ (Field work)(Diesel)(Costs)  │
│        ‹   2026   ›          │
│ [ Export PDF ][ Export Excel]│
│ Date    Parcel    Operation  │
│ 02.05   North 3   Sowing     │
│ 04.05   South-1   Spraying   │
└──────────────────────────────┘
```

### AI assistant

```text
┌──────────────────────────────┐
│ Assistant                    │
│ (North 3)(South-1)(Lunca M.) │
│  What should I grow next yr? │
│ Parcel North 3 has pH 6.8    │
│ and 3.9% organic matter.     │
│ Wheat or soybean is recomm.  │
│ [Your question           ] ➤ │
└──────────────────────────────┘
```
