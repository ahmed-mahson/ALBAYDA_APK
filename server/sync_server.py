#!/usr/bin/env python3
"""
Reference sync backend for Albayda Court app.

Run:  python sync_server.py
Then set the app's sync endpoint (admin screen) to:
  http://<server-ip>:8765      or  https://<your-deployed-host>

Endpoints:
  GET  /health
  GET  /events?since=<ISO timestamp>   -> JSON array of events
  POST /events                         -> JSON array of events to store

Events are immutable and identified by a UUID, so every device eventually
receives the same posts, news, registrations and notifications.
"""

import json
import os
import sqlite3
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

DB_PATH = os.environ.get("SYNC_DB", os.path.join(os.path.dirname(__file__), "sync.db"))
PORT = int(os.environ.get("PORT", "8765"))


def init_db():
    conn = sqlite3.connect(DB_PATH)
    conn.execute(
        """CREATE TABLE IF NOT EXISTS events (
               id TEXT PRIMARY KEY,
               type TEXT NOT NULL,
               payload TEXT NOT NULL,
               created_at TEXT NOT NULL
           )"""
    )
    conn.execute("CREATE INDEX IF NOT EXISTS idx_events_created_at ON events(created_at)")
    conn.commit()
    conn.close()


class Handler(BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")

    def _send(self, code, data):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self._cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self._send(204, {})

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            self._send(200, {"status": "ok", "utc": datetime.now(timezone.utc).isoformat()})
            return
        if parsed.path != "/events":
            self._send(404, {"error": "not found"})
            return
        since = parse_qs(parsed.query).get("since", [""])[0] or "1970-01-01T00:00:00.000Z"
        conn = sqlite3.connect(DB_PATH)
        rows = conn.execute(
            "SELECT id, type, payload, created_at FROM events WHERE created_at > ? ORDER BY created_at ASC LIMIT 500",
            (since,),
        ).fetchall()
        conn.close()
        self._send(
            200,
            [
                {"id": r[0], "type": r[1], "payload": json.loads(r[2]), "created_at": r[3]}
                for r in rows
            ],
        )

    def do_POST(self):
        if self.path != "/events":
            self._send(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            events = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(events, list):
                raise ValueError("expected JSON array")
        except Exception as exc:  # noqa: BLE001
            self._send(400, {"error": str(exc)})
            return
        conn = sqlite3.connect(DB_PATH)
        for event in events:
            conn.execute(
                "INSERT OR IGNORE INTO events(id, type, payload, created_at) VALUES(?,?,?,?)",
                (
                    str(event.get("id", "")),
                    str(event.get("type", "")),
                    json.dumps(event.get("payload", {}), ensure_ascii=False),
                    str(event.get("created_at", "")),
                ),
            )
        conn.commit()
        conn.close()
        self._send(200, {"saved": len(events)})

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    init_db()
    print(f"Albayda sync server listening on 0.0.0.0:{PORT} (db: {DB_PATH})")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
