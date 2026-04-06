# TransitLK — ETA Prediction Model (XGBoost)

> **Project:** TransitLK  
> **Author:** Vihanga Malith  
> **Pilot Route:** SLTB Bus Route No. 256 / 341-1 — Ratmalana Railway Station → Maharagama  
> **Algorithm:** XGBoost Regressor  
> **Environment:** Google Colab  

---

## Overview

This notebook implements an **Estimated Time of Arrival (ETA) prediction model** for the **TransitLK** project — a real-time public bus transit system built for Sri Lanka's bus network.

The model is trained on GPS trip log data recorded by a driver app during real-world runs on **SLTB Route 256 / 341-1**, operating between **Ratmalana Railway Station** and **Maharagama**. It learns temporal, spatial, and traffic-aware patterns from historical trips and predicts how many minutes remain until a bus reaches any upcoming stop along the route.

---

## Route Details

| Field       | Value                                         |
|-------------|-----------------------------------------------|
| Operator    | Sri Lanka Transport Board (SLTB)              |
| Route No.   | 256 / 341-1                                   |
| Origin      | Ratmalana Railway Station                     |
| Destination | Maharagama                                    |
| Direction   | Outbound & Return (bi-directional)            |
| Data Source | Real-world pilot test — driver app GPS logs   |

---

## Pipeline

```
1. Upload stops .xlsx        → Load stop names & coordinates (outbound + return)
2. Load trip CSVs            → GPS logs from Google Drive (recorded by driver app)
3. Label GPS points          → Tag each point with its nearest stop (Haversine, 25m radius)
4. Feature engineering       → Temporal, spatial, speed, sensor & interaction features
5. Train XGBoost             → Regressor predicting ETA in minutes to each upcoming stop
6. Evaluate                  → MAE, RMSE, R², per-stop accuracy, learning curves
7. Export                    → .pkl model + JSON metadata + stops JSON for the dashboard
```

---

## Inputs

### 1. Stops XLSX File
The same file used in the TransitLK admin app.

| Sheet   | Contents        |
|---------|-----------------|
| Sheet 1 | Outbound stops  |
| Sheet 2 | Return stops    |

**Required columns:** `StopName`, `Coordinates` (format: `6.8148, 79.8674`)

### 2. Trip CSV Files (Google Drive)
GPS logs recorded by the driver app. Stored in:
```
MyDrive/Colab Notebooks/TransitLK CSV Logs/
```

**Required columns:** `lat`, `lng`, `speed_mps`, `accuracy`  
**Optional (enhances accuracy):** `timestamp` (Unix ms or datetime string), `ax`, `ay`, `az`, `gx`, `gy`, `gz`

---

## Features (29 total)

| Category         | Features |
|------------------|----------|
| **Spatial**      | `lat`, `lng`, `dist_to_target_m`, `stops_remaining`, `last_stop_idx`, `target_stop_idx`, `progress_pct`, `is_outbound` |
| **Speed/Motion** | `speed_mps`, `speed_kmh`, `is_slow`, `is_stopped`, `accel_magnitude`, `gyro_magnitude`, `accuracy` |
| **Temporal**     | `hour`, `minute`, `day_of_week`, `is_weekend`, `is_rush_hour`, `time_of_day_min`, `hour_sin`, `hour_cos`, `dow_sin`, `dow_cos`, `elapsed_sec` |
| **Interaction**  | `traffic_score`, `rush_x_stops`, `dist_x_rush` |

> Hour and day-of-week are **cyclically encoded** (sin/cos) so that time boundaries like 23:00→00:00 and Sun→Mon are treated as continuous.  
> Rush hours are defined as **07:00–09:00** and **16:00–19:00**.

---

## Model Configuration

```python
XGBRegressor(
    n_estimators          = 500,
    max_depth             = 6,
    learning_rate         = 0.05,
    subsample             = 0.8,
    colsample_bytree      = 0.8,
    min_child_weight      = 3,
    gamma                 = 0.1,
    reg_alpha             = 0.1,   # L1 regularisation
    reg_lambda            = 1.0,   # L2 regularisation
    early_stopping_rounds = 30,
    eval_metric           = 'mae'
)
```

**Train/Test Split:** Split by trip file to prevent data leakage — the same trip cannot appear in both train and test sets. Falls back to a random 80/20 split if fewer than 4 trips are available.

---

## Evaluation Metrics

| Metric             | Description                                      |
|--------------------|--------------------------------------------------|
| **MAE**            | Mean Absolute Error in minutes *(primary metric)*|
| **RMSE**           | Root Mean Squared Error                          |
| **R²**             | Coefficient of Determination                     |
| **Within ±2 mins** | % of predictions accurate within 2 minutes      |
| **Within ±5 mins** | % of predictions accurate within 5 minutes      |
| **Within ±10 mins**| % of predictions accurate within 10 minutes     |

Evaluation also produces: per-stop MAE breakdowns, residual distributions, MAE by hour of day, MAE by day of week, and learning curves (train vs. test MAE over boosting rounds).

---

## Outputs

After training, three files are exported and auto-downloaded:

| File | Description |
|------|-------------|
| `TransitLK_{ROUTE_ID}_ETA.pkl` | Trained XGBoost model (joblib) |
| `TransitLK_{ROUTE_ID}_ETA_metadata.json` | Model config, feature list, performance metrics, training info |
| `TransitLK_{ROUTE_ID}_ETA_stops.json` | Stop coordinates, names, and average inter-stop travel times (used as a dashboard fallback when the model is unavailable) |

---

## Detection Configuration

```python
DETECTION_RADIUS  = 25   # metres  — GPS point counts as "at a stop" within this radius
MIN_STOP_DWELL    = 3    # seconds — minimum dwell time to register a real stop visit
GPS_INTERVAL_SEC  = 5    # seconds — interval between GPS readings (match your driver app)
MAX_ETA_MINUTES   = 120  # minutes — cap for outlier removal
```

---

## Requirements

```txt
pandas
numpy
matplotlib
seaborn
openpyxl
xgboost
scikit-learn
joblib
shap
```

Install:
```bash
pip install pandas numpy matplotlib seaborn openpyxl xgboost scikit-learn joblib shap
```

> This notebook is designed for **Google Colab** and uses `google.colab.files` for uploads/downloads and `google.colab.drive` to mount Google Drive for CSV loading.

---
## Project Structure

```
TransitLK_ETA_Prediction_XGB.ipynb     # Full pipeline: data ingestion → labelling → features → model → export
```

---

## About TransitLK

**TransitLK** is a project to modernise public bus transit in Sri Lanka by providing passengers with real-time bus tracking and accurate ETA predictions. This ETA prediction module is the core intelligence layer of the system, designed to integrate directly with the TransitLK driver app and passenger-facing dashboard.

---

## Author

**Vihanga Malith**  
TransitLK Project

---

## License

All rights reserved by the author unless otherwise stated.
