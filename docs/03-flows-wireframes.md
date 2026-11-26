# Fluxuri și wireframe-uri

## Fluxurile aplicației

### Creare parcelă

```text
Hartă → Parcelă nouă → atinge colțurile → suprafața se calculează live
     → completează numele → Salvează
     → API validează conturul și salvează parcela
     → SoilGrids rulează în fundal → profilul de sol apare în tabul Sol
```

### Evaluare rotație

```text
Parcelă → Rotație → API citește istoricul → motorul calculează scorul 0-100
       → ✅ Recomandat (70+) / ⚠️ Acceptabil (40-69) / ❌ Nerecomandat (sub 40)
       → explicația afișează motivul principal
```

### Lucrare live

```text
Work → parcelă, utilaj, lățime, viteză, eficiență
    → Capacitate = Lățime × Viteză × Eficiență / 10
    → Pornește → Socket.IO anunță echipa → cronometrul rulează pe telefon
    → Pauză / Reia / Finalizează, sau finalizare automată la 100%
    → consum de motorină calculat → notificare push
```

### Irigare

```text
Irigații → parcelă, sistem, traseu, viteză → durată = traseu / viteză
        → Pornește → cronometru live și procent realizat
        → la final: status actualizat și notificare push
        → doza aplicată intră în bilanțul de apă
```

### Alerte meteo

```text
La 30 de minute: prognoză pentru fiecare parcelă → reguli de alertă
→ alertă nouă? → salvare + Socket.IO + push
→ aceeași alertă nu se repetă în aceeași zi
```

### Sincronizare offline

```text
Fără semnal: citirile vin din SQLite, modificările intră în coadă
Semnalul revine: coada se trimite în ordine, cu cheie de idempotență
  2xx → operațiune ștearsă
  4xx → operațiune marcată și afișată în Setări
  rețea sau 5xx → se reia mai târziu
```

### Asistent AI

```text
Întrebare + parcelă → server adună istoric, sol, NDVI, lucrări, rotație, prognoză
→ Claude răspunde pe baza acestor date
→ fără cheie sau la eroare: rezumat calculat din aceleași date
```

## Wireframe-uri

### Dashboard

```text
┌──────────────────────────────┐
│ Ferma Demo               [⚙] │
│ ┌──────────────────────────┐ │
│ │ ▱ ▰ ▱ ▰ ▱   contururi    │ │
│ │ 12 parcele   270,4 ha    │ │
│ │ 133,5 ha     136,9 ha    │ │
│ │ cultivat     liber       │ │
│ └──────────────────────────┘ │
│ Alerte meteo                 │
│ ⛈ Ploaie în 3 ore, Sud-1     │
│ În desfășurare               │
│ Semănat, Nord 3         42%  │
│ ▓▓▓▓▓▓░░░░  se termină 16:40 │
│ Culturi în 2026              │
│ Grâu    ▓▓▓▓▓▓░░   84 ha     │
├──────────────────────────────┤
│ ▦   ◫   ⚙   ☑   💧  ▤   ✦   │
└──────────────────────────────┘
```

### Hartă

```text
┌──────────────────────────────┐
│                         [◎]  │
│    ╱▔▔▔▔╲  verde        [⇅]  │
│   ▕ Nord ▏                   │
│    ╲____╱     ╱▔▔▔╲ galben   │
│              ▕ Sud ▏         │
│               ╲___╱          │
│ ┌──────────────────────────┐ │
│ │ ▱ Nord 3, 68 ha, Grâu    │ │
│ │ NDVI 0,72                │ │
│ │ [ Deschide parcela ]     │ │
│ └──────────────────────────┘ │
└──────────────────────────────┘
```

### Editor parcelă

```text
┌──────────────────────────────┐
│ ← Parcelă                    │
│  ●───────●              [↶]  │
│  │ hartă  ╲             [🗑]  │
│  ●─────────●                 │
├──────────────────────────────┤
│ 68,42 ha      3.412 m        │
│ [Nume parcelă            ]   │
│ [Cod] [Localitate] [Județ]   │
│ [ Salvează parcela ]         │
└──────────────────────────────┘
```

### Detalii parcelă

```text
┌──────────────────────────────┐
│ ← Nord 3                 [✎] │
│ ▱  68,42 ha, NV-003          │
│ (Culturi)(Rotație)(Sol)(Mete…│
│ ┌──────────────────────────┐ │
│ │ Floarea-soarelui      22 │ │
│ │ ❌ Nerecomandat          │ │
│ │ A fost cultivată acum 2  │ │
│ │ ani. Pauză minimă 4 ani. │ │
│ └──────────────────────────┘ │
│ ┌──────────────────────────┐ │
│ │ Soia                  90 │ │
│ │ ✅ Recomandat            │ │
│ └──────────────────────────┘ │
└──────────────────────────────┘
```

### Work

```text
┌──────────────────────────────┐
│ Work                    [🚜] │
│ Semănat, Nord 3         42%  │
│ ▓▓▓▓▓▓░░░░░                  │
│ Scurs 02:14:08    ETA 16:40  │
│ [ Pauză ]  [ Finalizează ]   │
│ Pornește o lucrare           │
│ (Nord 3)(Sud-1)              │
│ (Arat)(Discuit)(Semănat)     │
│ [6 m] [8 km/h] [80 %]        │
│ 3,84 ha/oră  17,8 ore        │
│ [ Pornește lucrarea ]        │
└──────────────────────────────┘
```

### To Do

```text
┌──────────────────────────────┐
│ To Do                        │
│ (To Do)(In Progress)(Done)   │
│ ┃ Erbicidare         Începe  │
│ ┃ Sud-1, Ana, Ridicată       │
│ ┃ Termen: 20 nov.            │
│ Adaugă un task               │
│ (Fertilizare)(Erbicidare)    │
│ [Termen] (Parcelă)(Responsabil)│
│ [ Salvează task-ul ]         │
└──────────────────────────────┘
```

### Irigații

```text
┌──────────────────────────────┐
│ Irigații                     │
│ Lunca Mare              61%  │
│ Tambur, 400 m la 25 m/oră    │
│ ▓▓▓▓▓▓▓░░░░                  │
│ Scurs 09:45:12    ETA 22:00  │
│ [ Pauză ]  [ Finalizează ]   │
│ Pornește o irigare           │
│ (Tambur)(Pivot)(Liniar)      │
│ [Traseu m] [Viteză m/oră]    │
│ [ Pornește irigarea ]        │
└──────────────────────────────┘
```

### Rapoarte

```text
┌──────────────────────────────┐
│ Rapoarte                     │
│ (Lucrări)(Motorină)(Costuri) │
│        ‹   2026   ›          │
│ [ Export PDF ][ Export Excel]│
│ Data    Parcelă   Lucrare    │
│ 02.05   Nord 3    Semănat    │
│ 04.05   Sud-1     Erbicidat  │
└──────────────────────────────┘
```

### Asistent AI

```text
┌──────────────────────────────┐
│ Asistent                     │
│ (Nord 3)(Sud-1)(Lunca Mare)  │
│        Ce cultiv anul viitor?│
│ Parcela Nord 3 are pH 6,8 și │
│ materie organică 3,9%. Se    │
│ recomandă grâu sau soia.     │
│ [Întrebarea ta           ] ➤ │
└──────────────────────────────┘
```
