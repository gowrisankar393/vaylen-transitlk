"""
test_local.py — test the API locally before deploying to Railway

Usage:
    # Terminal 1
    export GOOGLE_APPLICATION_CREDENTIALS=./models/serviceAccount.json
    export FIREBASE_DB_URL=https://transitlk-79740-default-rtdb.firebaseio.com
    python app.py

    # Terminal 2
    python test_local.py
"""

import requests

BASE = "http://localhost:8080"


def test_health():
    r = requests.get(f"{BASE}/health")
    print("Health:", r.json())


def test_r2m():
    r = requests.post(f"{BASE}/predict", json={
        "bus_id":        "BUS_001",
        "route_id":      "256-341-1",
        "direction":     "R2M",
        "lat":           6.8121,
        "lng":           79.8813,
        "speed":         8.5,
        "accuracy":      10.0,
        "elapsed_sec":   300,
        "write_firebase": False,
    })
    data = r.json()
    print(f"\nR2M — {len(data.get('etas', []))} stops:")
    for e in data.get("etas", []):
        print(f"  {e['stopIdx']:2d}. {e['stopName']:<40} {e['arrivalTime']}  ({e['etaMins']}m)")


def test_m2r():
    r = requests.post(f"{BASE}/predict", json={
        "bus_id":        "BUS_001",
        "route_id":      "256-341-1",
        "direction":     "M2R",
        "lat":           6.8328,
        "lng":           79.9064,
        "speed":         6.0,
        "accuracy":      12.0,
        "elapsed_sec":   720,
        "write_firebase": False,
    })
    data = r.json()
    print(f"\nM2R — {len(data.get('etas', []))} stops:")
    for e in data.get("etas", []):
        print(f"  {e['stopIdx']:2d}. {e['stopName']:<40} {e['arrivalTime']}  ({e['etaMins']}m)")


if __name__ == "__main__":
    print("=" * 55)
    print("TransitLK ETA API — Local Test")
    print("=" * 55)
    try:
        test_health()
        test_r2m()
        test_m2r()
        print("\n✅ All tests passed!")
    except requests.exceptions.ConnectionError:
        print("\n❌ Could not connect — is the API running? (python app.py)")