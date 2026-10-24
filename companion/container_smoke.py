"""Run inside the built container: python - < container_smoke.py. No model needed."""
import json
import os
import ssl
import urllib.request
from cina import Store

store = Store(os.getenv("CINA_DATA", "/data"))
tls = ssl.create_default_context(cafile=str(store.directory / "cert.pem"))
tls.check_hostname = False

def call(path, body=None, token=None):
    req = urllib.request.Request("https://127.0.0.1:8443" + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": "Bearer " + token} if token else {})
    with urllib.request.urlopen(req, context=tls, timeout=10) as response:
        return json.load(response)

pair = call("/v1/pair", {"code": store.pair_code(), "name": "Container smoke test"})
assert pair["server_id"] == store.setting("id")
state = call("/v1/state", token=pair["token"])
assert state["protocol"] == 1
assert call("/v1/control", {"paused": True}, pair["token"])["paused"]
assert call("/v1/state", token=pair["token"])["paused"]
assert not call("/v1/control", {"paused": False}, pair["token"])["paused"]
assert call("/v1/device", {}, pair["token"])["unpaired"]
print("HTTPS, persistent storage, pairing and controls passed")
