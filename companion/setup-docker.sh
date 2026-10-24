#!/usr/bin/env bash
# Optional host provisioning, invoked only after explicit --install-docker or terminal consent.
set -Eeuo pipefail
fail() { printf 'Docker setup: %s\n' "$*" >&2; exit 1; }
((EUID == 0)) || fail 'Run this helper as root.'
[[ -r /etc/os-release ]] || fail 'Cannot identify this Linux distribution. Install Docker/Compose manually.'
# This is the host's trusted OS metadata, not a downloaded/user configuration file.
. /etc/os-release
case "${ID:-}:${VERSION_ID:-}" in
    ubuntu:22.04|ubuntu:24.04|ubuntu:26.04|ubuntu:26.10|debian:12|debian:13) ;;
    *) fail 'Automatic Docker installation supports Ubuntu 22.04/24.04/26.04/26.10 and Debian 12/13. Install Docker/Compose manually on this host.' ;;
esac
[[ "$(uname -r)" != *[Mm]icrosoft* ]] || fail 'Configure Docker Desktop integration or your WSL Docker engine manually, then rerun the Cina installer.'
command -v systemctl >/dev/null 2>&1 || fail 'Automatic Docker installation requires systemd.'
command -v apt-get >/dev/null 2>&1 || fail 'apt-get is required.'
command -v curl >/dev/null 2>&1 || fail 'Install curl and ca-certificates first.'
if command -v docker >/dev/null 2>&1; then
    docker info >/dev/null 2>&1 || fail 'An existing Docker engine is unavailable. Start/fix it before installing Compose; existing daemon settings were not changed.'
    # Do not replace/restart a running engine simply to add the Compose plugin.
    apt-cache show docker-compose-plugin >/dev/null 2>&1 || fail 'Install the Compose plugin using your Docker distribution instructions.'
    apt-get update
    apt-get install -y docker-compose-plugin
    exit 0
fi
for package in docker.io docker-compose docker-compose-v2 docker-doc docker-buildx podman-docker containerd runc; do
    if [[ "$(dpkg-query -W -f='${db:Status-Status}' "$package" 2>/dev/null || true)" == installed ]]; then
        fail "Existing package $package may conflict with Docker CE. Review Docker's distribution installation instructions; no packages were removed."
    fi
done
[[ ! -e /etc/apt/sources.list.d/docker.list ]] || fail 'An existing Docker repository configuration was found. Review it and install Docker/Compose manually.'
if [[ -e /etc/apt/sources.list.d/docker.sources || -e /etc/apt/keyrings/docker.asc ]]; then
    grep -qx '# Configured by Cina Home Docker setup' /etc/apt/sources.list.d/docker.sources 2>/dev/null || fail 'An existing Docker repository configuration was found. Review it and install Docker/Compose manually.'
fi
codename=${UBUNTU_CODENAME:-${VERSION_CODENAME:-}}
[[ "$codename" =~ ^[a-z][a-z0-9-]*$ ]] || fail 'Invalid OS codename.'
architecture=$(dpkg --print-architecture)
[[ "$architecture" =~ ^[a-z0-9]+$ ]] || fail 'Invalid architecture.'
printf 'Installing Docker Engine and Compose from Docker\047s official %s repository.\n' "$ID"
apt-get update
apt-get install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
key_temp=$(mktemp /etc/apt/keyrings/.cina-docker-key.XXXXXX)
repo_temp=''
cleanup() {
    [[ -z "$key_temp" ]] || rm -f -- "$key_temp"
    [[ -z "$repo_temp" ]] || rm -f -- "$repo_temp"
}
trap cleanup EXIT
curl -q --fail --silent --show-error --proto '=https' --tlsv1.2 "https://download.docker.com/linux/$ID/gpg" --output "$key_temp"
chmod a+r "$key_temp"
mv -f -- "$key_temp" /etc/apt/keyrings/docker.asc
key_temp=''
repo_temp=$(mktemp /etc/apt/sources.list.d/.cina-docker-repo.XXXXXX)
cat > "$repo_temp" <<EOF
# Configured by Cina Home Docker setup
Types: deb
URIs: https://download.docker.com/linux/$ID
Suites: $codename
Components: stable
Architectures: $architecture
Signed-By: /etc/apt/keyrings/docker.asc
EOF
chmod a+r "$repo_temp"
mv -f -- "$repo_temp" /etc/apt/sources.list.d/docker.sources
repo_temp=''
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
docker info >/dev/null
docker compose version
