# Cina Home validation — October 22, 2026

Deployment now uses Docker Compose directly; the earlier installer/bootstrap sections below record historical checks. See the latest Compose section for current validation.

Implemented: deployable CPU/NVIDIA Compose configurations; digest-pinned Python/Ollama images; private model runner; pinned HTTPS pairing and device revocation; model operations; durable digest-pinned analysis; pause/resume/cancel; worker controls; Android reconciliation and notifications in the existing Connections/Models/Chat/Work screens. An optional Home assignment handles recognisable Deep analysis while phone models remain available for everyday/offline requests and actions.

Local checks passed:

- 14 Python contract/recovery/runner-adapter tests, including idempotent submission, cancellation tombstones, owner isolation, crash recovery, model reservations/digest changes, layer progress, and incomplete-output rejection.
- 71 Android JVM unit tests.
- 121 Android regression cases: 111 passed, 10 explicitly opt-in cases skipped, no failures.
- Four additional opt-in Android checks passed against a real, pinned HTTPS fixture: pairing UI, model picker, durable answer, app recreation, Work notification routing, receipt replay, stale revisions, cancelled partial output and deleted-chat isolation. Inference was deterministic test data.
- The two-phase process test passed. The app persisted an accepted queued job, was force-stopped, and had no running process before or after the same server job completed. A new app process recovered its encrypted credential and delivered exactly one answer for that job. Preparation and verification each passed separately.
- The actual control service started locally with its generated certificate and passed HTTPS pairing, worker pause, persisted state and revocation checks without a model runner.
- Debug app/test APKs built. Lint: 0 errors, 81 warnings, including existing warnings and the deliberate certificate-pinning trust manager.
- Docker Hub confirmed both pinned multi-architecture image manifests exist. Compose/container smoke checks are added to CI.

Local evidence is retained in `.tools/home-server-tests.txt`, `home-final-regressions.txt`, `home-android-integration.txt`, `home-restart-prepare.txt`, `home-restart-offline.txt`, `home-restart-verify.txt`, and `home-control-smoke-result.txt`; build XML reports contain exact test counts.

Limits: Docker is not installed in the implementation workspace, so no local Docker build, Compose execution or actual GPU inference was possible. The new CI workflow has not been run remotely. The Ryzen 5600X/RTX 3070 still needs a host deployment and real model quality, latency and memory measurements. Browser/desktop execution, arbitrary tool agents, repeating server jobs, whole-document processing and server retention controls are outside this version.

## Terminal installer — October 23, 2026

Added `install.sh` to the deployable package. It checks a local Docker/Compose engine, suggests a private LAN/VPN IPv4 address, validates NVIDIA Docker access when selected, preserves existing settings/volumes, starts the services, waits for both APIs, and prints phone pairing details. Reruns retain accelerator mode and produce fresh pairing details. It does not provision system packages, drivers, Docker permissions or firewall rules.

Bash syntax and **29 tests passed locally**: the 14 existing server checks plus 15 installer checks using fake Docker/network commands. Installer coverage includes CPU/NVIDIA selection, custom address/port, remote-context rejection, settings preservation and repeatability, dotenv treated as data, GPU failure, startup failure/timeout, missing files and concurrent-run protection. Evidence: `.tools/companion-installer-tests.txt`. The CI workflow now exercises real CPU installation and a rerun with server identity preservation; it still requires a remote run. No Android code changed for this installer addition.

## GitHub bootstrap — October 24, 2026

Added `bootstrap.sh` for `curl ... | bash`, a component VERSION file, and an optional Ubuntu/Debian Docker provisioning helper. Bootstrap downloads pin one GitHub commit, finish staging before updating code, preserve configuration/data, and reconnect interactive stdin through the controlling terminal. System package installation requires a separate flag or terminal consent. Kernel/GPU drivers and existing Docker runtime configuration are outside automatic provisioning.

Local Bash syntax and **41 checks passed**, with **one Linux controlling-terminal check skipped on Windows** (42 cases total). Thirteen new bootstrap cases cover actual piped Bash execution, immutable revision selection, update preservation, partial download/truncated script handling, unrelated-directory rejection, remote contexts and explicit provisioning consent. Downloads, Docker and provisioning are mocked; host packages were not installed. The Linux terminal test and actual Docker deployment remain CI checks. Evidence: `.tools/companion-bootstrap-tests.txt`.

The published review branch subsequently passed [Linux CI at revision 48c55e19](https://github.com/Matt-Mc/Cina/actions/runs/40119885381): **all 42 tests**, actual installation through the public GitHub curl pipeline, a CPU Docker deployment, a rerun preserving server identity, and container HTTPS/pairing/control smoke checks. The first Docker run exposed source permissions inherited from private downloads; the image now uses `COPY --chmod=0444` so its unprivileged runtime can always read the server code. GPU execution, model quality/performance, and host package provisioning remain unmeasured on real target hardware. The mobile app and main branch were not published by this change.

## Direct Compose deployment — October 24, 2026

Simplified deployment to one downloadable Compose file, with an optional NVIDIA override saved as `compose.override.yaml` so ordinary Compose commands consistently retain GPU configuration. Docker fetches the previously validated server source at immutable revision `48c55e19b10c876b83a3429b5809b0254727345e`; development can explicitly select a local build context. Added a readiness check for pinned local TLS, required authentication and the private model API. The project and volume names are unchanged for existing deployments.

Removed the installer, GitHub bootstrap, host Docker provisioning helper and their mocked-command tests. The unpublished standalone uninstall script was also removed. Installation, updates, normal removal and optional data deletion now use standard Docker Compose operations. Docker and NVIDIA prerequisites remain managed by the server administrator.

All **14 server protocol/recovery tests passed locally** (`.tools/companion-compose-tests.txt`). Updated Linux CI deploys from a clean directory with only the downloaded Compose file and `.env`, verifies that the remote build contains the checked server code, exercises TLS/pairing/controls, removes and recreates the stack while preserving identity/database/model-volume data, then removes volumes explicitly and checks unrelated Docker resources survive. Remote results are recorded after completion. No Android runtime behavior changed for this simplification.
