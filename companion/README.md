# Cina Home companion

Cina Home runs local models and durable analysis jobs on your own computer. The Android app pairs through **Settings → Connections → + Add → Add your Home server**, manages server models in **Models**, and shows progress, pause/resume/cancel and results in **Work**. There is no additional dashboard or phone/server tab.

This first implementation supports text analysis, model downloads, load/unload/delete, durable submissions, restart recovery and app reconciliation. It has no browser, shell, general computer control, connected-service tools or scheduled/repeating server jobs yet. Phone tools and Android schedules continue to use phone models. Home answers are data and are never parsed into phone tool calls.

This is a preview server component. Pairing requires a companion-aware Cina Android build that includes **Home** under Connections; older mobile releases do not have that integration. Publishing this component does not publish or update the mobile app.

## Deploy

Use a Linux Docker host with Docker Compose. NVIDIA acceleration also needs the [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html). Ollama also documents [Docker on Windows with WSL2](https://docs.ollama.com/docker); that configuration has not been tested here. No access to your existing home server was available during implementation.

### Install directly from GitHub

Once these companion files are merged into `main`, the normal remote install command is:

```sh
curl -fsSL https://raw.githubusercontent.com/Matt-Mc/Cina/main/companion/bootstrap.sh | bash
```

For the published preview branch, use the same branch for the bootstrap URL and downloaded bundle:

```sh
curl -fsSL https://raw.githubusercontent.com/Matt-Mc/Cina/codex/companion-bootstrap/companion/bootstrap.sh | bash -s -- --ref codex/companion-bootstrap
```

No Git checkout, ZIP extraction or host Python is needed. The bootstrap resolves the requested GitHub branch/tag to one immutable commit, downloads the companion files over HTTPS, and installs them under `~/.local/share/cina-home`. It reconnects prompts to `/dev/tty`, so address selection and pairing setup work even though the script arrived through a pipe. All downloads finish before existing local code is replaced; `.env`, accelerator settings and Docker volumes are retained. Repeating the command updates the code and generates fresh pairing details without unpairing existing phones. The selected repository and commit are recorded in `.cina-source`.

On a fresh Ubuntu/Debian host without Docker/Compose, interactive setup offers to install them from Docker's official package repository. `--install-docker` explicitly permits that step for headless setup; `--yes` alone does not permit system package installation. Supported host provisioning targets are Ubuntu 22.04/24.04/26.04/26.10 and Debian 12/13 with systemd. Other distributions and WSL must provide Docker/Compose themselves. Existing conflicting packages are not removed, existing Docker daemon configuration is not rewritten, and group membership is not changed. A newly installed rootful engine may run the Cina setup through sudo.

For example, after publication to `main`, a fresh host with configured NVIDIA drivers/toolkit can use:

```sh
curl -fsSL https://raw.githubusercontent.com/Matt-Mc/Cina/main/companion/bootstrap.sh | bash -s -- --install-docker --gpu
```

For unattended CPU setup (run as root or with appropriate sudo access):

```sh
curl -fsSL https://raw.githubusercontent.com/Matt-Mc/Cina/main/companion/bootstrap.sh | bash -s -- --install-docker --cpu --host 192.168.1.20 --yes
```

Use `--dir /srv/cina-home` to choose another permanent directory, or `--ref TAG_OR_FULL_COMMIT_SHA` to select a specific revision. For forks, `--repo OWNER/REPO` changes the download source. GPU drivers and NVIDIA Container Toolkit remain host prerequisites; the bootstrap does not install kernel drivers or reconfigure an existing Docker runtime. Initial model downloads remain in the Cina app. For a fresh pairing code without updating code, run the saved installer, for example `bash ~/.local/share/cina-home/install.sh`.

### Terminal installer

Extract the complete companion package into a directory you own, then run from that directory:

```sh
bash install.sh
```

From a source checkout, `bash companion/install.sh` works from the repository root. Keep the other companion files beside the script; downloading `install.sh` alone is insufficient. The installer checks Docker and Compose, suggests a private LAN/VPN IPv4 address, detects configured NVIDIA support, writes the connection settings, builds/starts both services, waits for the encrypted API and model runner, and prints a one-use pairing code for the phone. No model weights are downloaded automatically; choose a model from Cina after pairing.

For the RTX 3070, require GPU operation explicitly:

```sh
bash install.sh --gpu
```

For unattended setup or a specific network address:

```sh
bash install.sh --host 192.168.1.20 --gpu --yes
# Or choose CPU operation explicitly:
bash install.sh --host 192.168.1.20 --cpu --yes
```

Optional `--port 9443` changes the HTTPS port; `--timeout 300` allows longer startup. `--help` lists all options. Use the LAN/VPN address assigned to this server and reachable by your phone. The script runs against a local Docker engine and rejects remote Docker contexts. Docker publishes Cina's HTTPS port at the chosen address; the installer does not separately configure your firewall or install Docker/drivers/toolkit, change Docker permissions, or restart the daemon. Missing prerequisites produce links to their official setup instructions. If your local Docker socket requires root, run `sudo bash install.sh` after reviewing the script.

Rerun the installer to apply the current bundle or generate a fresh pairing code. It preserves other `.env` settings, records CPU/GPU choice in `.cina-install-mode`, and keeps the named volumes containing identity, paired devices, models and jobs. Once CPU/GPU mode is recorded, it stays selected on reruns unless `--cpu` or `--gpu` overrides it. GPU failures stop setup with instructions; they do not silently switch to CPU. An interrupted or failed startup leaves the services and data available for diagnosis. Keep the installed directory for subsequent updates.

### Manual setup

From this directory:

```sh
cp .env.example .env
```

Edit `CINA_BIND_IP` to the server's LAN or VPN address, for example `192.168.1.20`. The default `127.0.0.1` accepts only connections from the server itself. Allow the configured HTTPS port (default 8443) from your phone's LAN/VPN. Keep the service on your private network; do not forward it directly from the internet.

For the Ryzen 5600X / RTX 3070 target:

```sh
docker compose -f compose.yaml -f compose.gpu.yaml config
docker compose -f compose.yaml -f compose.gpu.yaml up -d --build
```

For CPU-only deployment, omit both `-f` arguments:

```sh
docker compose up -d --build
```

Two services run: a small Cina control service and a private Ollama model runner. Only Cina's HTTPS port is published. Persistent named volumes keep the server identity, certificate, paired devices, job journal and model files. The control service runs as an unprivileged user with a read-only image, dropped capabilities and no Docker socket or host-directory mounts. Ollama's cloud features are disabled with `OLLAMA_NO_CLOUD=1`; model downloads still require internet access.

Generate pairing details on the host (replace the address with your server's actual address):

```sh
docker compose exec companion python cina.py pair --url https://192.168.1.20:8443
```

Copy the complete JSON object into Cina's **Pairing details** field. It contains a certificate fingerprint and a one-use code valid for ten minutes. Only copy details generated on your own server. The phone checks the fingerprint before sending the code; a different certificate is rejected. Tokens are encrypted using Android Keystore on the phone, and only token hashes are stored by the server. An expired code can be replaced by running the same command again. If pairing succeeds on the server but the response is lost, generate a new code and revoke the unused device later.

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

The Python base and Ollama runner are pinned to Docker Hub manifest digests verified on October 22, 2026. Update the Dockerfile base and `CINA_OLLAMA_IMAGE` deliberately when upgrading. Model tags can move, so record the digest shown in the runner inventory for benchmark evidence. Upgrade with the same Compose files used at deployment and `up -d --build`. No Cina image registry or automatic container updater is configured.

## Verify

```sh
python -m unittest discover -s companion -v
# Run from repository root. No third-party Python packages or model weights needed.
```

CI validates Compose, builds the control image and runs an HTTPS pairing/control smoke test against its real read-only container and named volume. `integration_fixture.py` is a loopback-only fake model runner for the optional Android `HomeCompanionFlowTest`; it is excluded from the image. That test uses a genuine pinned HTTPS connection, pairing UI, the existing model picker, durable result delivery and Work. It is not an inference benchmark.

Docker and a GPU are unavailable in the implementation workspace. Container build/GPU execution must therefore be confirmed on a Docker host; the added CI checks run when pushed. The Python service and Android integration can be verified locally without them. Real 3070 memory, speed and model quality remain unmeasured. See [the model evaluation evidence and product design](../docs/companion-and-consolidation-2026-10-21.md) and [the phone benchmark runner](../tools/Measure-CinaModels.ps1).

To reproduce the opt-in phone restart check, use a disposable Android emulator and the built debug/test APKs. Start `integration_fixture.py` with a disposable `CINA_DATA` directory and OpenSSL on PATH, then forward emulator port 18443 with `adb reverse tcp:18443 tcp:18443`. Pass the base64-encoded `bundle.json` as the instrumentation argument `cinaHomeBundle` to `HomeCompanionFlowTest` for the UI check. `HomeCompanionRestartTest` accepts `cinaHomeRestart=prepare` with a fresh pairing bundle; it persists a queued job while the worker is paused. Force-stop the app, unpause the fixture's worker on the host, wait for its SQLite job to finish, then run `cinaHomeRestart=verify` in a new app process. Verification checks the same server/job identity and exactly one delivered chat answer. These tests skip ordinary runs without explicit fixture arguments.
