"""Container check: download a tiny public GGUF through the authenticated Home API."""
import json
import ssl
import time
import urllib.request
import uuid

from cina import Store

store = Store("/data")
tls = ssl.create_default_context(cafile=str(store.directory / "cert.pem"))
tls.check_hostname = False


def call(path, body=None, token=None):
    request = urllib.request.Request("https://127.0.0.1:8443" + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": "Bearer " + token} if token else {})
    with urllib.request.urlopen(request, context=tls, timeout=15) as response:
        return json.load(response)


pair = call("/v1/pair", {"code": store.pair_code(), "name": "Hub download smoke"})
token = pair["token"]
assert "huggingface" in call("/v1/state", token=token)["model_sources"]
name = "hf.co/ggml-org/tiny-llamas:stories15M-q4_0.gguf"
job_id = str(uuid.uuid4())
call("/v1/jobs", {"id": job_id, "kind": "download", "model": name, "title": "Download tiny public Hub fixture", "prompt": ""}, token)
deadline = time.monotonic() + 180
while time.monotonic() < deadline:
    job = call("/v1/jobs/status", {"ids": [job_id]}, token)["jobs"][0]
    if job["state"] == "completed":
        break
    assert job["state"] not in {"failed", "interrupted", "cancelled"}, job.get("detail", "Hub download failed")
    time.sleep(1)
else:
    raise AssertionError("Tiny Hub fixture download did not complete in time")
for _ in range(30):
    models = call("/v1/state", token=token)["models"]
    installed = next((model for model in models if model["name"].casefold() == name.casefold()), None)
    if installed:
        break
    time.sleep(1)
assert installed and installed["bytes"] > 0 and installed["digest"], models
call("/v1/device", {}, token)
print("Authenticated Home download of the 19 MB public Hugging Face GGUF passed; model is installed with a digest.")
