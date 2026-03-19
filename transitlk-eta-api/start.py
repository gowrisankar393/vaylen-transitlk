"""
start.py — Railway entrypoint
Starts the Firebase listener in a background thread, then starts gunicorn.
"""

import os
import sys
import time
import threading
import logging

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("start")


def run_listener():
    time.sleep(3)  # wait for Flask to be ready first
    log.info("Starting Firebase listener...")
    try:
        from firebase_listener import start_listener
        start_listener()
    except Exception as e:
        log.exception("Firebase listener crashed: %s", e)


# Start listener in background
threading.Thread(target=run_listener, daemon=True).start()

# Start gunicorn in foreground
port    = os.environ.get("PORT", "8080")
workers = os.environ.get("GUNICORN_WORKERS", "2")

log.info("Starting gunicorn on port %s...", port)
os.execv(sys.executable, [
    sys.executable, "-m", "gunicorn",
    "--bind", f"0.0.0.0:{port}",
    "--workers", workers,
    "--timeout", "60",
    "app:app"
])