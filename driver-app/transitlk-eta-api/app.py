"""
TransitLK ETA Prediction API
Flask backend for Railway — watches Firebase for live bus GPS,
runs XGBoost model, writes ETA predictions back to Firebase.
"""

import os
import json
import math
import logging
import threading
from datetime import datetime, timezone
from math import radians, sin, cos, sqrt, atan2

import joblib
import numpy as np
from flask import Flask, request, jsonify
import firebase_admin
from firebase_admin import credentials, db

# ── Logging ───────────────────────────────────────────────────────────────────
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("transitlk-eta")

# ── App ───────────────────────────────────────────────────────────────────────
app = Flask(__name__)

# ── Firebase init ─────────────────────────────────────────────────────────────
_fb_app = None

def get_firebase():
    global _fb_app
    if _fb_app:
        return

    firebase_db_url = os.environ.get(
        "FIREBASE_DB_URL", "https://transitlk-79740-default-rtdb.firebaseio.com"
    )

    # Priority 1: FIREBASE_SERVICE_ACCOUNT env var (JSON string) — Railway
    service_account_json = os.environ.get("FIREBASE_SERVICE_ACCOUNT")
    if service_account_json:
        try:
            cred = credentials.Certificate(json.loads(service_account_json))
            log.info("Firebase: using service account from FIREBASE_SERVICE_ACCOUNT env var")
        except Exception as e:
            raise RuntimeError(f"FIREBASE_SERVICE_ACCOUNT is invalid JSON: {e}")

    # Priority 2: path to serviceAccount.json file — local dev
    elif os.environ.get("GOOGLE_APPLICATION_CREDENTIALS"):
        cred = credentials.Certificate(os.environ["GOOGLE_APPLICATION_CREDENTIALS"])
        log.info("Firebase: using service account file")

    else:
        raise RuntimeError(
            "No Firebase credentials found.\n"
            "  On Railway: set FIREBASE_SERVICE_ACCOUNT env var (paste the full JSON)\n"
            "  Locally:    set GOOGLE_APPLICATION_CREDENTIALS to path of serviceAccount.json"
        )

    _fb_app = firebase_admin.initialize_app(cred, {"databaseURL": firebase_db_url})
    log.info("Firebase initialized — %s", firebase_db_url)


# ── Model registry ────────────────────────────────────────────────────────────
_models: dict = {}
_stops:  dict = {}
_model_lock = threading.Lock()

MODEL_DIR = os.environ.get("MODEL_DIR", "./models")
STOPS_DIR = os.environ.get("STOPS_DIR", "./models")

# Feature list — must match training notebook exactly
FEATURES = [
    "lat", "lng",
    "dist_to_target_m", "stops_remaining",
    "last_stop_idx", "target_stop_idx",
    "progress_pct", "is_outbound",
    "speed_mps", "speed_kmh",
    "is_slow", "is_stopped",
    "accel_magnitude", "gyro_magnitude",
    "accuracy",
    "hour", "minute", "day_of_week",
    "is_weekend", "is_rush_hour", "time_of_day_min",
    "hour_sin", "hour_cos",
    "dow_sin", "dow_cos",
    "elapsed_sec",
    "traffic_score", "rush_x_stops", "dist_x_rush",
]


def load_route(route_id: str) -> bool:
    """Load model + stops JSON for a route. Returns True on success."""
    with _model_lock:
        if route_id in _models:
            return True
        pkl_path   = os.path.join(MODEL_DIR, f"TransitLK_{route_id}_ETA.pkl")
        stops_path = os.path.join(STOPS_DIR, f"TransitLK_{route_id}_ETA_stops.json")

        if not os.path.exists(pkl_path):
            log.error("Model not found: %s", pkl_path)
            return False
        if not os.path.exists(stops_path):
            log.error("Stops not found: %s", stops_path)
            return False

        try:
            _models[route_id] = joblib.load(pkl_path)
            with open(stops_path) as f:
                _stops[route_id] = json.load(f)
            log.info("Route %s loaded OK", route_id)
            return True
        except Exception as e:
            log.exception("Failed to load route %s: %s", route_id, e)
            return False


# ── Geo helpers ───────────────────────────────────────────────────────────────
def haversine(lat1, lng1, lat2, lng2) -> float:
    R = 6_371_000
    φ1, φ2 = radians(lat1), radians(lat2)
    dφ = radians(lat2 - lat1)
    dλ = radians(lng2 - lng1)
    a = sin(dφ / 2) ** 2 + cos(φ1) * cos(φ2) * sin(dλ / 2) ** 2
    return R * 2 * atan2(sqrt(a), sqrt(1 - a))


def find_nearest_stop_idx(lat, lng, stops) -> int:
    best_idx, best_dist = 0, float("inf")
    for i, s in enumerate(stops):
        d = haversine(lat, lng, s["lat"], s["lng"])
        if d < best_dist:
            best_dist, best_idx = d, i
    return best_idx


# ── Feature engineering ───────────────────────────────────────────────────────
def build_feature_row(lat, lng, speed_mps, accuracy, is_outbound,
                      last_stop_idx, target_stop_idx, total_stops,
                      elapsed_sec, dt, target_lat, target_lng,
                      accel_mag=0.0, gyro_mag=0.0) -> list:

    dist        = haversine(lat, lng, target_lat, target_lng)
    hour        = dt.hour
    minute      = dt.minute
    dow         = dt.weekday()
    is_weekend  = int(dow >= 5)
    is_rush     = int((7 <= hour <= 9) or (16 <= hour <= 19))
    tod_min     = hour * 60 + minute
    speed_kmh   = speed_mps * 3.6
    is_slow     = int(speed_kmh < 10)
    is_stopped  = int(speed_kmh < 2)
    progress    = (last_stop_idx / max(total_stops - 1, 1)) * 100
    stops_rem   = total_stops - target_stop_idx
    traffic     = is_rush * 2 + is_slow + is_stopped
    rush_stops  = is_rush * stops_rem
    dist_rush   = dist * (1 + is_rush * 0.3)

    feat = {
        "lat": lat, "lng": lng,
        "dist_to_target_m": dist,
        "stops_remaining":  stops_rem,
        "last_stop_idx":    last_stop_idx,
        "target_stop_idx":  target_stop_idx,
        "progress_pct":     progress,
        "is_outbound":      is_outbound,
        "speed_mps":        speed_mps,
        "speed_kmh":        speed_kmh,
        "is_slow":          is_slow,
        "is_stopped":       is_stopped,
        "accel_magnitude":  accel_mag,
        "gyro_magnitude":   gyro_mag,
        "accuracy":         accuracy,
        "hour":             hour,
        "minute":           minute,
        "day_of_week":      dow,
        "is_weekend":       is_weekend,
        "is_rush_hour":     is_rush,
        "time_of_day_min":  tod_min,
        "hour_sin":         math.sin(2 * math.pi * hour / 24),
        "hour_cos":         math.cos(2 * math.pi * hour / 24),
        "dow_sin":          math.sin(2 * math.pi * dow / 7),
        "dow_cos":          math.cos(2 * math.pi * dow / 7),
        "elapsed_sec":      elapsed_sec,
        "traffic_score":    traffic,
        "rush_x_stops":     rush_stops,
        "dist_x_rush":      dist_rush,
    }
    return [feat[f] for f in FEATURES]


# ── Core prediction ───────────────────────────────────────────────────────────
def predict_etas(route_id: str, bus_data: dict) -> list:
    if not load_route(route_id):
        raise ValueError(f"Route {route_id!r} not loaded")

    model       = _models[route_id]
    stops_cfg   = _stops[route_id]
    direction   = bus_data["direction"]
    is_outbound = 1 if direction == "R2M" else 0
    stops       = stops_cfg["outbound"] if direction == "R2M" else stops_cfg["return"]
    total_stops = len(stops)

    lat         = float(bus_data["lat"])
    lng         = float(bus_data["lng"])
    speed_mps   = float(bus_data.get("speed", 0))
    accuracy    = float(bus_data.get("accuracy", 15))
    elapsed_sec = float(bus_data.get("elapsed_sec", 0))
    accel_mag   = float(bus_data.get("accel_magnitude", 0))
    gyro_mag    = float(bus_data.get("gyro_magnitude", 0))
    now         = datetime.now(tz=timezone.utc)

    last_stop_idx = find_nearest_stop_idx(lat, lng, stops)

    rows, targets = [], []
    for i in range(last_stop_idx, total_stops):
        s = stops[i]
        rows.append(build_feature_row(
            lat, lng, speed_mps, accuracy, is_outbound,
            last_stop_idx, i, total_stops, elapsed_sec, now,
            s["lat"], s["lng"], accel_mag, gyro_mag
        ))
        targets.append((i, s))

    if not rows:
        return []

    preds = model.predict(np.array(rows, dtype=np.float32))

    results = []
    for (stop_idx, stop), eta_mins in zip(targets, preds):
        eta_mins   = max(0.0, float(eta_mins))
        arrival_ts = now.timestamp() + eta_mins * 60
        arrival_dt = datetime.fromtimestamp(arrival_ts, tz=timezone.utc)
        results.append({
            "stopIdx":      stop_idx,
            "stopName":     stop["name"],
            "lat":          stop["lat"],
            "lng":          stop["lng"],
            "etaMins":      round(eta_mins, 1),
            "arrivalTime":  arrival_dt.strftime("%H:%M"),
            "arrivalEpoch": int(arrival_ts),
        })
    return results


# ── Firebase write ────────────────────────────────────────────────────────────
def write_etas_to_firebase(bus_id, route_id, direction, etas):
    get_firebase()
    db.reference(f"buses/{bus_id}/etas").set({
        "route_id":   route_id,
        "direction":  direction,
        "updated_at": int(datetime.now(tz=timezone.utc).timestamp() * 1000),
        "stops":      etas,
    })
    log.info("ETAs written → bus=%s  route=%s  dir=%s  stops=%d",
             bus_id, route_id, direction, len(etas))


# ── API routes ────────────────────────────────────────────────────────────────
@app.get("/health")
def health():
    return jsonify({"status": "ok", "loaded_routes": list(_models.keys())})


@app.post("/predict")
def predict():
    """
    POST /predict
    {
        "bus_id":        "BUS_001",
        "route_id":      "256-341-1",
        "direction":     "R2M",       // R2M = outbound, M2R = return
        "lat":           6.8148,
        "lng":           79.8674,
        "speed":         8.3,         // m/s
        "accuracy":      10.0,        // metres
        "elapsed_sec":   420,         // seconds since trip started
        "write_firebase": true        // write ETAs back to Firebase
    }
    """
    data = request.get_json(force=True, silent=True)
    if not data:
        return jsonify({"error": "Missing JSON body"}), 400

    missing = [k for k in ["bus_id", "route_id", "direction", "lat", "lng"] if k not in data]
    if missing:
        return jsonify({"error": f"Missing fields: {missing}"}), 400

    if data["direction"] not in ("R2M", "M2R"):
        return jsonify({"error": "direction must be R2M or M2R"}), 400

    try:
        etas = predict_etas(data["route_id"], data)
    except ValueError as e:
        return jsonify({"error": str(e)}), 404
    except Exception as e:
        log.exception("Prediction error: %s", e)
        return jsonify({"error": "Prediction failed"}), 500

    if data.get("write_firebase"):
        try:
            write_etas_to_firebase(data["bus_id"], data["route_id"], data["direction"], etas)
        except Exception as e:
            log.warning("Firebase write failed (non-fatal): %s", e)

    return jsonify({"bus_id": data["bus_id"], "route_id": data["route_id"],
                    "direction": data["direction"], "etas": etas})


@app.post("/predict/batch")
def predict_batch():
    """POST /predict/batch  { "buses": [ <predict_body>, ... ] }"""
    data = request.get_json(force=True, silent=True)
    if not data or "buses" not in data:
        return jsonify({"error": "Expected { buses: [...] }"}), 400

    results = []
    for bus in data["buses"]:
        try:
            etas = predict_etas(bus.get("route_id", ""), bus)
            if bus.get("write_firebase"):
                try:
                    write_etas_to_firebase(bus["bus_id"], bus["route_id"], bus["direction"], etas)
                except Exception as e:
                    log.warning("Firebase write failed for %s: %s", bus.get("bus_id"), e)
            results.append({"bus_id": bus.get("bus_id"), "etas": etas})
        except Exception as e:
            results.append({"bus_id": bus.get("bus_id"), "error": str(e)})

    return jsonify({"results": results})


@app.post("/reload/<route_id>")
def reload_route(route_id):
    """Force-reload a route model without restarting."""
    with _model_lock:
        _models.pop(route_id, None)
        _stops.pop(route_id, None)
    ok = load_route(route_id)
    return jsonify({"status": "reloaded" if ok else "failed", "route_id": route_id}), (200 if ok else 500)


# ── Startup ───────────────────────────────────────────────────────────────────
def preload_models():
    if not os.path.isdir(MODEL_DIR):
        log.warning("MODEL_DIR %s not found", MODEL_DIR)
        return
    for fname in os.listdir(MODEL_DIR):
        if fname.endswith("_ETA.pkl"):
            route_id = fname.removeprefix("TransitLK_").removesuffix("_ETA.pkl")
            load_route(route_id)

preload_models()

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 8080)), debug=False)