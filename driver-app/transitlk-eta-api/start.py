

import os
import sys
import time
import threading
import logging

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("start")


def run_listener():
    time.sleep(5)  # wait for gunicorn to be ready first
    log.info("Starting Firebase listener thread...")
    try:
        from firebase_listener import start_listener
        start_listener()
    except Exception as e:
        log.exception("Firebase listener crashed: %s", e)


def keep_alive():
    """Ping the health endpoint every 10 minutes to prevent Render free tier sleep."""
    import requests
    url = os.environ.get("ETA_API_URL", "https://transitlk-backend.onrender.com")
    time.sleep(60)  # wait for server to be ready
    while True:
        try:
            r = requests.get(f"{url}/health", timeout=10)
            log.info("Keep-alive ping: %s", r.status_code)
        except Exception as e:
            log.warning("Keep-alive ping failed: %s", e)
        time.sleep(600)  # ping every 10 minutes


# Start Firebase listener thread
threading.Thread(target=run_listener, daemon=True).start()

# Start keep-alive thread (only on Render)
if os.environ.get("RENDER_KEEP_ALIVE"):
    threading.Thread(target=keep_alive, daemon=True).start()
    log.info("Keep-alive thread started")

# Start gunicorn in foreground
port    = os.environ.get("PORT", "8080")
workers = os.environ.get("GUNICORN_WORKERS", "2")

log.info("Starting gunicorn on port %s...", port)
os.execv(sys.executable, [
    sys.executable, "-m", "gunicorn",
    "--bind", f"0.0.0.0:{port}",
    "--workers", workers,
    "--timeout", "120",
    "app:app"
])
