# Cina Home companion

Cina Home runs local models and durable analysis jobs on your own computer. The Android app pairs through **Settings → Connections → + Add → Add your Home server**, manages server models in **Models**, and shows progress, pause/resume/cancel and results in **Work**. There is no additional dashboard or phone/server tab.

This first implementation supports text analysis, model downloads, load/unload/delete, durable submissions, restart recovery and app reconciliation. It has no browser, shell, general computer control, connected-service tools or scheduled/repeating server jobs yet. Phone tools and Android schedules continue to use phone models. Home answers are data and are never parsed into phone tool calls.

Pairing requires a Cina Android build that includes **Home** under Connections. Update through **Settings → About & updates** or install the [latest signed Android release](https://github.com/Matt-Mc/Cina/releases/latest); builds 22 and earlier do not include companion support.

## Deploy with Docker Compose

Use a Linux server with Docker Engine, Compose v2 and its Buildx plugin installed. NVIDIA acceleration also needs the NVIDIA driver and [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html). See [Docker's installation instructions](https://docs.docker.com/engine/install/) for your distribution. Cina uses standard Docker commands for installation, updates and removal.

### 1. Download the Compose file

```sh
mkdir -p ~/cina-home
cd ~/cina-home
curl -fL https://raw.githubusercontent.com/Matt-Mc/Cina/main/companion/compose.yaml -o compose.yaml
```

Only `compose.yaml` is required for CPU deployment. Docker downloads a pinned revision of the server source from GitHub and builds it automatically, so no local source checkout, Git, Python or installer script is needed. The first build and model-runner image download can take several minutes.

### 2. Set your server address

Create a `.env` file beside `compose.yaml`, replacing the example with the server's LAN/VPN address reachable from your phone:

```sh
printf 'CINA_BIND_IP=192.168.1.20\n' > .env
```

For an existing installation, edit its `.env` instead of replacing it. The default address is `127.0.0.1`, which accepts connections only from the server itself. Optional settings are `CINA_PORT` (default `8443`), `CINA_NAME` (default `Home`) and `CINA_CONTEXT` (default `4096`). Allow the HTTPS port from your phone's LAN/VPN; keep it on your private network.

For your **RTX 3070**, also download the NVIDIA override before starting:

```sh
curl -fL https://raw.githubusercontent.com/Matt-Mc/Cina/main/companion/compose.gpu.yaml -o compose.override.yaml
```

Compose automatically includes `compose.override.yaml` for every command in this directory, keeping GPU selection consistent during updates and removal. Omit it for CPU operation. Docker must already have GPU access; a missing driver/toolkit produces a startup error.

### 3. Start and pair

```sh
docker compose up -d --build --wait --wait-timeout 180
docker compose exec companion python cina.py pair --url https://192.168.1.20:8443
```

Use the address and port from `.env` in the pairing command. `--wait` checks both the encrypted API and private model runner. If startup fails, inspect `docker compose logs --tail 100` and rerun after resolving the error.

Copy the complete JSON object into Cina under **Settings → Connections → + Add → Add your Home server → Pairing details**. It contains a certificate fingerprint and a one-use code valid for ten minutes. Generate a fresh code with the same command whenever needed. Only copy details generated on your own server. The phone checks the fingerprint before sending the code; credentials are encrypted using Android Keystore and the server stores only token hashes. An acknowledged pairing cannot be reused; if the response is lost, generate a new code and revoke the unused device later.

No model weights are downloaded automatically. Once paired, choose a model in Cina's existing Models screen.

Two services run: a small Cina control service and a private Ollama model runner. Only Cina's HTTPS port is published. Named volumes preserve server identity, certificate, paired devices, jobs and model files. The control service runs as an unprivileged user with a read-only image, dropped capabilities and no Docker socket or host-directory mounts. Ollama's cloud features are disabled with `OLLAMA_NO_CLOUD=1`; model downloads still need internet access.

### Stop or uninstall

Run these commands from the directory containing your Compose file:

```sh
# Stop and remove the containers/network; keep models, pairing and work.
docker compose down
# Start again with the same data.
docker compose up -d --build --wait --wait-timeout 180
```

To permanently delete **all companion models, server work history, identity and paired-device credentials** as well:

```sh
docker compose down --volumes
```

This leaves Docker, GPU drivers, downloaded images and the small setup files in place. It does not remove results already saved on your phone. After deleting the volumes, use **Forget on this phone** for the old Home connection; a new deployment needs pairing again.

Earlier script-based deployments use the same `cina-companion` project and named volumes. Copy their existing `.env` into this directory and, if they used NVIDIA, download the override above. Compose adopts their containers/data without rerunning the scripts. Keep the project name unchanged when moving directories. If you still use the original source bundle with explicit `-f compose.yaml -f compose.gpu.yaml` arguments, continue supplying both arguments or save the GPU override as `compose.override.yaml`.

## Use it from Cina

1. Open **Models → Discover** and choose a Home candidate. Start with `qwen3:4b`; try `qwen3:8b` after measuring memory and latency. Downloads run on Home and report layer progress in Work. Other local Ollama model names can be entered; registry URLs and cloud tags are rejected.
2. In **Models → Installed**, select **Use** for a Home model to send analysis to it. The existing chat model picker also lists Home and Phone models. Home receives the current request, up to six bounded chat excerpts and up to three extracted attachment passages. It does not receive your memory catalogue, tool permissions or connection credentials.
3. Alternatively choose **Use for Deep** on a Home model, then keep a phone model selected. Auto/Deep sends recognisable read-only analysis to Home when it is reachable. Quick, action requests and work started while Home is unavailable use the phone model. This conservative English-language routing policy is implemented by the app, not by a small model; it intentionally leaves ambiguous requests on the phone. Explicit Home selection always uses Home and queues offline requests for Home.
4. Work continues after Cina closes. The result appears in Work and in its original chat after reconciliation. Foreground polling is about five seconds. Android background retries use backoff and a 15-minute periodic catch-up; results/notifications can be delayed by Android. Notifications contain no passage or answer content. Opening Cina reconciles again.
5. **Pause** waits for the current inference/download request to close before acknowledging **Paused**. **Resume** restarts analysis from the beginning; model downloads reuse completed layers. A crashed server marks running analysis **Interrupted**, requiring explicit Resume. Completed actions are never inferred from partial output. **Pause new work** in Connections leaves the current job running and keeps the control API available.

Each immutable request gets a phone-persisted UUID before submission. The server commits it before acknowledging it. Replays return the original job, conflicting payloads are rejected, cancellation tombstones cover lost acknowledgements, and chat delivery plus its receipt share one phone database transaction. Accepted jobs never silently fall back to the phone. If the server loses a job, the phone reports an unknown outcome rather than resubmitting it.

Analysis jobs pin the installed model digest. Model replacement/deletion/unloading is rejected while unfinished analysis reserves that model. If a host administrator changes the model out of band, the affected job fails rather than using different weights. Model changes are serial jobs too; an interrupted destructive operation needs its inventory checked before another operation is requested. The current service uses one execution slot and a 4096-token context with at most 2048 generated tokens; large passages are excerpts, not whole-file processing. A failed/paused job can remain visible for review.

## Manage, upgrade and recover

```sh
# Inventory paired devices without showing tokens
docker compose exec companion python cina.py devices
# Revoke a lost phone (replace ID)
docker compose exec companion python cina.py revoke --device DEVICE_ID
# List job IDs/states without printing prompts or answers
docker compose exec companion python cina.py jobs
# Control orphaned work after a lost phone or revoked pairing
docker compose exec companion python cina.py control --job JOB_UUID --action cancel
# Check the actual model placement on CPU/GPU
docker compose exec model ollama ps
# Inspect startup/runtime errors
docker compose logs --tail 100 companion model
# Stop services, preserving models, identity and jobs
docker compose down
```

Disconnect in the app revokes that pairing after server acknowledgement. Accepted work continues on the server. A later pairing has a new device identity and cannot retrieve the earlier pairing's jobs; cancel work before disconnecting when appropriate. If the server is unreachable or its certificate changed, **Forget on this phone** removes the saved connection so you can pair again; it does not revoke the old token or stop old jobs. Use the host commands above to revoke the device and control orphaned jobs. A stopped container must be restarted from the host, because its own API is then unavailable.

Back up both named volumes while the stack is stopped. The data volume contains sensitive prompts/results, certificate private key and device hashes; protect it like your other personal data. Restoring it preserves server identity and job IDs. Do not use `docker compose down --volumes` unless intentionally deleting all server data and models. A new data volume creates a new identity and requires pairing again. There is no retention/purge UI in v1.

### Update

Download the newer `compose.yaml` into the same directory (and the newer GPU override if used), then run:

```sh
docker compose up -d --build --wait --wait-timeout 180
```

Keep `.env` and the named volumes to retain models, pairing and work. The Compose file pins the server source to an immutable GitHub commit and the model-runner image to a manifest digest. Updating the Compose file deliberately advances those pins; rebuilding an unchanged file uses the same server source. No Cina image registry or automatic updater is required. Model tags can move, so record the installed digest when benchmarking.

For development from a source checkout, run `CINA_BUILD_CONTEXT=. docker compose up -d --build --wait` inside `companion/` to build your local server changes. The standard file otherwise always builds its pinned remote source, even when run from a checkout.

## Verify

```sh
python -m unittest discover -s companion -v
# Run from repository root. No third-party Python packages or model weights needed.
```

[Linux CI passed](https://github.com/Matt-Mc/Cina/actions/runs/40136959747): both Compose configurations, deployment from only the downloaded Compose file and a one-line `.env`, and the real container's TLS, pairing and controls. Removal/redeployment retained data; removal with `--volumes` deleted Cina's volumes while unrelated Docker resources survived. `integration_fixture.py` is a loopback-only fake model runner for the optional Android `HomeCompanionFlowTest`; it is excluded from the image. That test uses a genuine pinned HTTPS connection, pairing UI, the existing model picker, durable result delivery and Work. It is not an inference benchmark.

Docker and a GPU are unavailable in the implementation workspace. Linux CI validates container builds and CPU deployment; actual GPU execution requires target hardware. The Python service and Android integration can also be verified locally without Docker. Real 3070 memory, speed and model quality remain unmeasured. See [the model evaluation evidence and product design](../docs/companion-and-consolidation-2026-10-21.md) and [the phone benchmark runner](../tools/Measure-CinaModels.ps1).

To reproduce the opt-in phone restart check, use a disposable Android emulator and the built debug/test APKs. Start `integration_fixture.py` with a disposable `CINA_DATA` directory and OpenSSL on PATH, then forward emulator port 18443 with `adb reverse tcp:18443 tcp:18443`. Pass the base64-encoded `bundle.json` as the instrumentation argument `cinaHomeBundle` to `HomeCompanionFlowTest` for the UI check. `HomeCompanionRestartTest` accepts `cinaHomeRestart=prepare` with a fresh pairing bundle; it persists a queued job while the worker is paused. Force-stop the app, unpause the fixture's worker on the host, wait for its SQLite job to finish, then run `cinaHomeRestart=verify` in a new app process. Verification checks the same server/job identity and exactly one delivered chat answer. These tests skip ordinary runs without explicit fixture arguments.
