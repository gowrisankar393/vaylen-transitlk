"""
firebase_listener.py — watches Firebase buses/ and calls /predict for in-trip buses
"""

import os
import json
import time
import logging
import threading
import requests

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("firebase_listener")

ETA_API_URL = os.environ.get("ETA_API_URL", "http://localhost:8080")

def init_firebase():
    """Initialize Firebase Admin SDK from env var or file."""
    import firebase_admin
    from firebase_admin import credentials, db

    if firebase_admin._DEFAULT_APP_NAME in firebase_admin._apps:
        log.info("Firebase already initialized")
        return db

    db_url = os.environ.get("FIREBASE_DB_URL")
    if not db_url:
        raise ValueError("FIREBASE_DB_URL env var not set")
    log.info("Firebase DB URL: %s", db_url)

    sa_json = os.environ.get("FIREBASE_SERVICE_ACCOUNT")
    if sa_json:
        log.info("Loading Firebase credentials from FIREBASE_SERVICE_ACCOUNT env var")
        try:
            sa_dict = json.loads(sa_json)
        except json.JSONDecodeError as e:
            raise ValueError(f"FIREBASE_SERVICE_ACCOUNT is not valid JSON: {e}")
        cred = credentials.Certificate(sa_dict)
    else:
        # Fall back to file
        sa_file = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "models/serviceAccount.json")
        log.info("Loading Firebase credentials from file: %s", sa_file)
        if not os.path.exists(sa_file):
            raise FileNotFoundError(f"Service account file not found: {sa_file}")
        cred = credentials.Certificate(sa_file)

    firebase_admin.initialize_app(cred, {"databaseURL": db_url})
    log.info("Firebase initialized OK")
    return db


def predict_eta(bus_id, bus_data):
    """Call the local /predict endpoint and return ETA result or None."""
    try:
        payload = {
            "bus_id": bus_id,
            "lat": bus_data.get("lat", 0),
            "lng": bus_data.get("lng", 0),
            "speed": bus_data.get("speed", 0),
            "heading": bus_data.get("heading", 0),
            "timestamp": bus_data.get("timestamp", int(time.time() * 1000)),
        }
        r = requests.post(f"{ETA_API_URL}/predict", json=payload, timeout=15)
        if r.status_code == 200:
            return r.json()
        else:
            log.warning("Predict returned %s for %s: %s", r.status_code, bus_id, r.text[:200])
            return None
    except Exception as e:
        log.warning("Predict error for %s: %s", bus_id, e)
        return None


def write_etas_to_firebase(db, bus_id, eta_result):
    """Write ETA predictions back to Firebase under buses/{bus_id}/etas."""
    try:
        ref = db.reference(f"buses/{bus_id}/etas")
        ref.set(eta_result)
        log.info("✅ %s | ETAs written to Firebase (%d stops)", bus_id, len(eta_result.get("stops", [])))
    except Exception as e:
        log.error("Failed to write ETAs for %s: %s", bus_id, e)


def clear_etas(db, bus_id):
    """Remove ETA data when bus ends trip."""
    try:
        db.reference(f"buses/{bus_id}/etas").delete()
    except Exception:
        pass


def start_listener():
    """Main listener loop — watches buses/ and predicts ETAs for in-trip buses."""
    log.info("Initializing Firebase...")
    try:
        db = init_firebase()
    except Exception as e:
        log.error("Firebase init failed: %s", e)
        return

    log.info("🚌 Listening to Firebase buses/...")

    prev_in_trip = {}

    while True:
        try:
            buses_ref = db.reference("buses")
            buses = buses_ref.get()

            if not buses:
                log.info("No buses in Firebase yet")
                time.sleep(10)
                continue

            for bus_id, bus_data in buses.items():
                if not isinstance(bus_data, dict):
                    continue

                in_trip = bus_data.get("inTrip", False)
                online  = bus_data.get("online", False)
                lat     = bus_data.get("lat", 0)
                lng     = bus_data.get("lng", 0)

                # Bus ended trip — clear ETAs
                if prev_in_trip.get(bus_id) and not in_trip:
                    log.info("🔴 %s ended trip — clearing ETAs", bus_id)
                    clear_etas(db, bus_id)

                prev_in_trip[bus_id] = in_trip

                if not in_trip or not online:
                    continue

                if not lat or not lng:
                    log.warning("Skipping %s — no GPS coordinates", bus_id)
                    continue

                log.info("🔄 Predicting ETA for %s (lat=%.4f, lng=%.4f)", bus_id, lat, lng)
                result = predict_eta(bus_id, bus_data)

                if result:
                    write_etas_to_firebase(db, bus_id, result)
                else:
                    log.warning("No ETA result for %s", bus_id)

            time.sleep(10)  # Poll every 10 seconds

        except Exception as e:
            log.error("Listener loop error: %s", e)
            time.sleep(10)
