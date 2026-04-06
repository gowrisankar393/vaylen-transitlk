# TransitLK Admin Dashboard

> **Project:** TransitLK  
> **Author:** Gowrisankar Sivakumar  
> **Module:** Admin Dashboard — Fleet Management System  
> **Type:** Single-page web application (HTML/CSS/JS)  
> **Backend:** Firebase (Realtime Database + Storage) · ETA backend hosted on Render  
> **Powered by:** Vaylen

---

## Overview

The **TransitLK Admin Dashboard** is the central control panel for bus operators. It provides a real-time view of the entire fleet — where every bus is, what it's doing, its predicted arrival times at upcoming stops, and any safety incidents flagged by the onboard AI models.

Live telemetry is sent from the **driver app** (on the driver's phone) → **Firebase Realtime Database** → **ETA backend on Render** (where ML predictions are computed) → back to **Firebase** → consumed here in the admin dashboard and by the **passenger apps**.

---

## System Data Flow

```
Driver's Phone (Driver App)
        │
        │  GPS, IMU, speed, status — live telemetry
        ▼
Firebase Realtime Database  ←──────────────────────┐
        │                                          │
        │  Raw bus data                             │  ETA predictions written back
        ▼                                          │
  Render Backend (ETA Model)  ──────────────────────┘
        │
        │  ML ETA results → Firebase
        │
        ├──▶  Admin Dashboard (this app)
        └──▶  Passenger Apps
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI Framework | Vanilla HTML/CSS/JS, Tailwind CSS v2 |
| Fonts | Sora (UI), JetBrains Mono (data) |
| Maps | Leaflet.js v1.9.4 with CartoDB Dark basemap |
| Realtime Data | Firebase Realtime Database v10.7.1 |
| File Storage | Firebase Storage v10.7.1 |
| Auth | Firebase Authentication (email/password) |
| Spreadsheet Export | SheetJS (xlsx.js) |
| ETA Backend | Hosted on Render (external service) |

---

## Authentication

The app is protected by Firebase email/password authentication. A **"Remember me"** option persists the session across page reloads using `localStorage`. Sessions are validated on every load via `isSessionValid()` — users are automatically redirected to the login screen if their session has expired.

---

## Dashboard Sections

The main dashboard is organised into three primary tabs:

### 1. Trip Logs
Displays all recorded bus trips stored in Firebase Storage under the `trips/` path, organised by date. Each trip entry shows the date, file name, and size. Features include:

- **Date filter** — All / Today / This Week / This Month
- **Search** by bus ID or trip ID
- **Trip detail modal** — opens a Leaflet map replaying the GPS route, with metrics:
  - Total distance
  - Trip duration
  - Average speed
  - Max speed
  - Start and end coordinates
  - Number of GPS points recorded
- **Download as CSV** — exports the raw GPS log for the trip

### 2. Incidents
Displays all safety incidents logged in Firebase Storage under the `incidents/` path, organised by date. Incidents are flagged by the onboard AI models (crash detection, drowsiness, foul activity). Features include:

- **Date filter** — All / Today / This Week / This Month
- **Incident detail view** with timestamp, bus ID, and incident file data
- Incident count shown as a live stat on the dashboard header

### 3. Live Fleet Tracking
A real-time map (Leaflet, dark CartoDB tiles) showing every bus in the fleet. The map is centred on Colombo, Sri Lanka. Features include:

- **Live bus markers** — animated, heading-aware icons on the map
- **Bus status indicators** — colour-coded per status:

  | Status | Colour |
  |--------|--------|
  | In Trip | Purple |
  | Online (Idle) | Yellow |
  | Parked | Blue |
  | Offline | Red |

- **Bus list sidebar** — filterable by status chip (All / In Trip / Idle / Parked / Offline / Deactivated)
- **Bus search** — search by plate number or bus ID
- **Click to focus** — clicking a bus on the map or sidebar focuses the view and draws the active route stops
- **ETA panel** — per-bus sliding panel showing ETAs to all upcoming stops, sourced from:
  1. **ML ETAs** — predictions from the Render backend (used if fresh, < 60 seconds old)
  2. **Fallback** — average inter-stop travel times from the route config
- **Recenter button** — snaps the map back to the fleet
- **Fullscreen mode** — toggles fullscreen for the live map panel
- **Online bus count** — live badge showing number of buses currently active

---

## Bus Detail Modal

Clicking any bus opens a detailed modal with **six tabs**:

| Tab | Description |
|-----|-------------|
| **Details** | Edit plate number, depot, route number, notes. Toggle bus active/inactive. Enable/disable Official Trip Mode. Delete bus from fleet. |
| **ETA** | Live ETA table for all upcoming stops on the current trip direction |
| **Route** | View and edit the bus's assigned route stops (outbound and return). Upload stops via `.xlsx` file. |
| **Timetable** | Upload and preview the scheduled timetable via `.xlsx` file |
| **Alternates** | Configure alternative starting points for outbound and return directions |
| **Schedule** | Preview the uploaded timetable schedule for Ratmalana → Maharagama and Maharagama → Ratmalana |

---

## Bus Data Fields (Live Telemetry)

The following fields are read live from Firebase for each bus:

| Field | Description |
|-------|-------------|
| `lat` / `lng` | Current GPS coordinates |
| `speed` | Current speed |
| `heading` | Direction of travel (degrees) |
| `status` | Bus operational status |
| `battery` | Device battery level |
| `lastSeen` | Timestamp of last telemetry update |
| `plate` | Vehicle registration number |
| `routeNo` | Assigned route number |
| `depot` | Home depot |
| `tripNo` | Current trip number |
| `inTrip` | Whether the bus is actively on a trip |
| `online` | Whether the bus is currently connected |
| `etas.stops` | ML ETA predictions array (written by Render backend) |

---

## Route & Stop Management

Routes are stored in Firebase and managed directly from the admin UI:

- **Multi-route support** — admin can create, label and switch between multiple routes via pill selectors
- **Outbound & Return stops** — each route has two independent stop lists (`stopsR2M` and `stopsM2R`)
- **Stop upload** — stops can be uploaded via `.xlsx` file (matching the format used by the ETA training notebook — `StopName`, `Coordinates` columns)
- **Timetable upload** — scheduled departure times uploaded via `.xlsx`
- **Alternate start points** — per-direction alternate starting stops can be configured and saved
- **Active route selection** — per-bus active route can be set directly from the bus detail modal
- **Direction detection** — direction (outbound/return) is auto-detected from the bus's GPS position relative to the first stop of each direction

---

## Dashboard Stats (Header)

| Stat | Description |
|------|-------------|
| **Total Trips** | All trips ever recorded in Firebase Storage |
| **Total Incidents** | All incidents ever recorded in Firebase Storage |
| **Today's Trips** | Trips recorded on the current calendar date |
| **Storage Used** | Combined size of all trip and incident files (MB) |

---

## Firebase Structure

| Firebase Service | Path | Contents |
|-----------------|------|----------|
| Realtime Database | `buses/` | Live telemetry, ETA predictions, bus admin config per bus ID |
| Storage | `trips/` | GPS trip CSV logs, organised by date subfolder |
| Storage | `incidents/` | Incident files (crash/drowsiness/foul), organised by date subfolder |
| Realtime Database | `routes/` | Route definitions — stop coordinates, timetables, alternate starts |

---

## Running the App

The admin dashboard is a single self-contained HTML file — no build step required.

1. Open `index.html` in any modern browser.
2. Log in with authorised Firebase credentials.
3. The dashboard loads trip and incident data from Firebase Storage automatically.
4. Switch to the **Live** tab to begin real-time fleet tracking.

> For local development, ensure your browser allows ES module imports or serve the file via a local HTTP server (e.g. `npx serve .` or VS Code Live Server) to avoid CORS issues with Firebase SDK imports.

---

## Key Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Tailwind CSS | 2.2.19 | Utility-first styling |
| Leaflet.js | 1.9.4 | Interactive maps |
| Firebase SDK | 10.7.1 | Auth, Realtime DB, Storage |
| SheetJS (xlsx) | 0.18.5 | `.xlsx` upload/export |
| Sora (Google Fonts) | — | UI typography |
| JetBrains Mono | — | Monospace data display |
| CartoDB Dark tiles | — | Dark map basemap |

---

## Design System

The dashboard uses a custom dark glass-morphism design system built on top of Tailwind:

- **Colour palette:** Deep near-black base (`#080c0b`), accent green (`#7ee85a`), teal (`#2dd4bf`), orange (`#f97316`)
- **Glass panels:** `backdrop-filter: blur` surfaces with subtle borders and inset highlights
- **Per-card hover glows** — each card type has a colour-matched glow on hover (green for stats, orange for incidents, etc.)
- **Animated bus markers** — pulsing SVG icons on the live map
- **Smooth tab transitions** with active state underlines

---

## About TransitLK

**TransitLK** is a project to modernise public bus transit in Sri Lanka. The Admin Dashboard is the operator-facing control layer, sitting at the centre of a system that connects the driver app, the AI models (ETA prediction, crash detection, drowsiness detection, foul activity detection), the Render-hosted backend, and the passenger-facing apps.

---

## Author

**Gowrisankar Sivakumar**  
TransitLK Project — Admin Dashboard

---

## License

All rights reserved unless otherwise stated.
