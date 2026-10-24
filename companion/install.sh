#!/usr/bin/env bash
# Install from the extracted companion bundle or this repository's companion directory.
set -Eeuo pipefail
umask 077

usage() {
    cat <<'HELP'
Cina Home companion installer (Linux, including WSL with a working Docker engine)

  bash install.sh [--host LAN_IPV4] [--port 8443] [--gpu | --cpu] [--yes]

  --host IP     Server LAN/VPN IPv4 address reachable from your phone
  --port PORT   HTTPS port (default: existing setting or 8443)
  --gpu         Require NVIDIA Docker GPU access; fail if unavailable
  --cpu         Run without GPU access
  --yes         Accept the displayed settings without terminal prompts
  --timeout N   Readiness timeout in seconds (default 180; maximum 3600)
  -h, --help    Show this help

Requires Docker Engine and the Docker Compose plugin. GPU mode also requires
an NVIDIA driver and NVIDIA Container Toolkit already configured for Docker.
Docker publishes Cina's HTTPS port on the address you select. This script does
not install system packages, restart Docker, separately configure firewalls or
change Docker group membership. Run it on the actual Docker host.

Rerunning preserves model files, pairing identity and jobs, updates the services
from this bundle, and generates a fresh 10-minute pairing code. Existing paired
phones remain paired. Keep the whole companion bundle beside this script.
HELP
}

say() { printf '%s\n' "$*"; }
fail() { printf 'Cina setup: %s\n' "$*" >&2; exit 1; }
host_arg='' port_arg='' mode='auto' yes=0 wait_seconds=180
while (($#)); do
    case "$1" in
        --host|--port|--timeout)
            (($# >= 2)) && [[ -n "$2" && "$2" != --* ]] || fail "$1 needs a value."
            case "$1" in --host) host_arg=$2 ;; --port) port_arg=$2 ;; --timeout) wait_seconds=$2 ;; esac
            shift 2 ;;
        --gpu) [[ "$mode" != cpu ]] || fail 'Choose either --gpu or --cpu.'; mode='gpu'; shift ;;
        --cpu) [[ "$mode" != gpu ]] || fail 'Choose either --gpu or --cpu.'; mode='cpu'; shift ;;
        --yes) yes=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) fail "Unknown option: $1. Use --help for usage." ;;
    esac
done
[[ "$wait_seconds" =~ ^[0-9]{1,4}$ ]] && ((10#$wait_seconds > 0 && 10#$wait_seconds <= 3600)) || fail 'Timeout must be between 1 and 3600 seconds.'
wait_seconds=$((10#$wait_seconds))

[[ "$(uname -s)" == Linux ]] || fail 'Run this script on a Linux Docker host, or in WSL with Docker available. See README.md for manual deployment.'
[[ -n "${BASH_SOURCE[0]:-}" && -f "${BASH_SOURCE[0]}" ]] || fail 'Save and extract the companion bundle first, then run bash install.sh.'
install_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
cd -- "$install_dir"
for file in compose.yaml compose.gpu.yaml Dockerfile cina.py .env.example; do
    [[ -f "$file" ]] || fail "Missing $file. Extract the complete companion package beside install.sh."
done
[[ -w "$install_dir" ]] || fail 'The companion directory is not writable. Extract the package into a directory you own.'
command -v docker >/dev/null 2>&1 || fail 'Docker is required. Install Docker Engine and its Compose plugin first: https://docs.docker.com/engine/install/ . Then rerun this installer.'

# Do not accidentally deploy to a remote Docker context using this machine's LAN address.
endpoint=${DOCKER_HOST:-}
if [[ -n "${DOCKER_CONTEXT:-}" ]]; then
    endpoint=$(docker context inspect "$DOCKER_CONTEXT" --format '{{.Endpoints.docker.Host}}') || fail 'Could not inspect the selected Docker context.'
elif [[ -z "$endpoint" ]]; then
    endpoint=$(docker context inspect --format '{{.Endpoints.docker.Host}}') || fail 'Could not inspect the current Docker context.'
fi
[[ "$endpoint" == unix://* ]] || fail 'Select a local Docker engine and run the installer on that host. Remote Docker contexts are not supported.'
docker info >/dev/null 2>&1 || fail 'Cannot reach Docker. Start the engine and check your access. If the local Docker socket requires root, rerun sudo bash install.sh. No permissions were changed.'
docker compose version >/dev/null 2>&1 || fail 'The Docker Compose plugin is required: https://docs.docker.com/compose/install/ .'

env_file='.env.example'
if [[ -e .env || -L .env ]]; then
    [[ -f .env && ! -L .env ]] || fail '.env must be a regular file, not a symbolic link.'
    env_file='.env'
fi
[[ ! -L .cina-install-mode ]] || fail '.cina-install-mode must not be a symbolic link.'
# Compose parses dotenv as data. Never execute/source an existing .env file.
resolved=$(docker compose --project-name cina-companion --env-file "$env_file" -f compose.yaml config --environment) || fail 'Existing Compose settings are invalid. Review .env and retry.'
setting() { awk -v key="$1=" 'index($0,key)==1 {print substr($0,length(key)+1); exit}' <<< "$resolved"; }

valid_ipv4() {
    local octet
    local -a octets
    [[ "$1" =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]] || return 1
    IFS=. read -r -a octets <<< "$1"
    for octet in "${octets[@]}"; do
        [[ "$octet" == 0 || "$octet" != 0* ]] || return 1
        ((10#$octet <= 255)) || return 1
    done
    ((octets[0] > 0 && octets[0] < 224 && octets[0] != 127))
}
private_ipv4() {
    valid_ipv4 "$1" || return 1
    local -a octets
    IFS=. read -r -a octets <<< "$1"
    ((octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) ||
      (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) ||
      (octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127)))
}
detect_host() {
    local candidate
    if command -v ip >/dev/null 2>&1; then
        candidate=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<NF;i++) if($i=="src") {print $(i+1); exit}}') || candidate=''
        if private_ipv4 "$candidate"; then say "$candidate"; return; fi
        while IFS= read -r candidate; do
            if private_ipv4 "$candidate"; then say "$candidate"; return; fi
        done < <(ip -o -4 addr show scope global 2>/dev/null | awk '$2 !~ /^(docker[0-9]*|br-|veth|virbr|vboxnet|vmnet|cni|flannel)/ {split($4,a,"/");print a[1]}')
    fi
    return 0
}

host=${host_arg:-$(setting CINA_BIND_IP)}
if [[ -z "$host_arg" ]] && ! private_ipv4 "$host"; then host=$(detect_host); fi
if ((yes == 0)) && [[ -t 0 ]]; then
    read -r -p "Server LAN/VPN IPv4 address${host:+ [$host]}: " reply
    host=${reply:-$host}
fi
valid_ipv4 "$host" || fail 'Supply a server LAN/VPN IPv4 address, for example --host 192.168.1.20. Loopback and wildcard addresses cannot pair a phone.'
# Public addresses require an explicit operator choice; auto-detection only selects private/VPN IPs.
if ! private_ipv4 "$host" && [[ -z "$host_arg" ]]; then fail 'For a non-private address, pass --host explicitly. Prefer a private LAN or VPN address.'; fi
port=${port_arg:-$(setting CINA_PORT)}
port=${port:-8443}
[[ "$port" =~ ^[0-9]{1,5}$ ]] && ((10#$port > 0 && 10#$port <= 65535)) || fail 'Port must be a number from 1 to 65535.'
port=$((10#$port))

if [[ "$mode" == auto && -f .cina-install-mode ]]; then
    read -r previous_mode < .cina-install-mode || true
    previous_mode=${previous_mode%$'\r'}
    case "${previous_mode:-}" in cpu|gpu) mode=$previous_mode ;; *) fail 'Invalid saved accelerator mode. Choose --cpu or --gpu explicitly.' ;; esac
fi
if [[ "$mode" == auto ]]; then
    if command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi -L >/dev/null 2>&1; then mode='gpu'; else mode='cpu'; fi
fi
say 'Cina Home setup'
say "  Directory: $install_dir"
say "  Address:   https://$host:$port"
say "  Compute:   $mode"
say 'Existing identity, paired phones, models and jobs will be preserved.'
say 'The HTTPS port is bound only to the address shown. Keep it on your LAN/VPN.'
if ((yes == 0)); then
    [[ -t 0 ]] || fail 'No interactive terminal. Review the settings, then rerun with --yes and --host.'
    read -r -p 'Set up and start Cina Home? [Y/n] ' reply
    case "$reply" in ''|y|Y|yes|YES) ;; *) say 'Setup cancelled. No settings changed.'; exit 0 ;; esac
fi

mkdir .cina-install.lock 2>/dev/null || fail 'Another installer is running, or a previous run left .cina-install.lock. Check that no installer is running before removing that empty directory.'
env_temp='' mode_temp=''
cleanup() {
    [[ -z "$env_temp" ]] || rm -f -- "$env_temp"
    [[ -z "$mode_temp" ]] || rm -f -- "$mode_temp"
    rmdir -- "$install_dir/.cina-install.lock" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

compose=(docker compose --project-name cina-companion --env-file .env -f compose.yaml)
if [[ "$mode" == gpu ]]; then
    image=$(setting CINA_OLLAMA_IMAGE)
    [[ -n "$image" ]] || fail 'CINA_OLLAMA_IMAGE is missing from the environment template/settings.'
    say 'Checking NVIDIA access in a temporary model container (the first run downloads its image)...'
    if ! docker run --rm --gpus all --entrypoint nvidia-smi -- "$image" -L; then
        fail 'Docker cannot use the NVIDIA GPU. Configure the NVIDIA driver and Container Toolkit, then rerun: https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html . Use --cpu to choose CPU operation explicitly. Docker was not reconfigured.'
    fi
    compose+=(-f compose.gpu.yaml)
else
    say 'CPU mode selected. Use --gpu to request NVIDIA acceleration.'
fi

# Replace only installer-managed keys; preserve all other user settings verbatim.
env_temp=$(mktemp "$install_dir/.env.install.XXXXXX")
awk '!/^[[:space:]]*(CINA_BIND_IP|CINA_PORT)[[:space:]]*=/' "$env_file" > "$env_temp"
printf 'CINA_BIND_IP=%s\nCINA_PORT=%s\n' "$host" "$port" >> "$env_temp"
# Explicit selections must also win over any exported values in the launching shell.
export CINA_BIND_IP="$host" CINA_PORT="$port"
docker compose --project-name cina-companion --env-file "$env_temp" -f compose.yaml config --quiet
mv -f -- "$env_temp" .env
env_temp=''
"${compose[@]}" config --quiet
say 'Starting the local model runner and building the companion...'
"${compose[@]}" up -d --build
mode_temp=$(mktemp "$install_dir/.mode.install.XXXXXX")
printf '%s\n' "$mode" > "$mode_temp"
mv -f -- "$mode_temp" .cina-install-mode
mode_temp=''

say 'Waiting for the encrypted API and model runner...'
ready=0
deadline=$((SECONDS + wait_seconds))
while ((SECONDS < deadline)); do
    if "${compose[@]}" exec -T companion python -c '
import ssl,urllib.request,urllib.error
tls=ssl.create_default_context(cafile="/data/cert.pem");tls.check_hostname=False
try:
    urllib.request.urlopen("https://127.0.0.1:8443/v1/state",context=tls,timeout=3)
    raise SystemExit("API did not require pairing")
except urllib.error.HTTPError as error:
    assert error.code==401
with urllib.request.urlopen("http://model:11434/api/tags",timeout=3) as response:
    assert response.status==200
' >/dev/null 2>&1; then ready=1; break; fi
    sleep 2
done
if ((ready == 0)); then
    say "Startup did not become ready within $wait_seconds seconds. Services and data were left in place." >&2
    "${compose[@]}" ps >&2 || true
    say 'Inspect startup errors with: docker compose logs --tail 100 companion model' >&2
    exit 1
fi

say ''
say 'Cina Home is ready. On your phone open Settings → Connections → + Add → Add your Home server.'
say 'Paste the complete pairing details below. This one-use code expires in 10 minutes:'
"${compose[@]}" exec -T companion python cina.py pair --url "https://$host:$port"
say ''
say 'Next: open Models → Discover in Cina to download a model on Home.'
say 'Run this installer again to update from this bundle or generate a fresh pairing code.'
say 'Keep the named Docker volumes to preserve models, pairing identity and work.'
