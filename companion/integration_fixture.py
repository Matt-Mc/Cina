"""Loopback-only deterministic HTTPS fixture for the opt-in Android integration test.

Run with a disposable CINA_DATA directory; never deploy this fake inference runner.
"""
import hashlib
import json
import os
from pathlib import Path
import ssl
import threading
import time

from cina import SecureServer, Service, Store, certificate
from test_cina import FakeRunner


class DelayedRunner(FakeRunner):
    def execute(self, job, update, stopped, context):
        for i in range(20):
            if stopped():
                return ""
            update("Analysing test passage", "Partial fixture answer")
            time.sleep(0.1)
        return "Verified fixture answer"


def main():
    store = Store(os.environ["CINA_DATA"])
    cert, key = certificate(store.directory)
    fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest()
    # Android reaches this fixture through adb reverse; the server only listens on loopback.
    bundle = {"url": "https://127.0.0.1:18443", "fingerprint": fingerprint, "server_id": store.setting("id"), "code": store.pair_code()}
    (store.directory / "bundle.json").write_text(json.dumps(bundle))
    service = Service(store, DelayedRunner()); service.refresh_inventory()
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); tls.load_cert_chain(cert, key)
    server = SecureServer(("127.0.0.1", 18443), tls); server.service = service
    threading.Thread(target=service.run, daemon=True).start()
    threading.Thread(target=service.monitor, daemon=True).start()
    print("HTTPS fixture ready", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
