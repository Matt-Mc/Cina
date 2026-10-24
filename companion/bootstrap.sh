#!/usr/bin/env bash
# Keep execution inside main so a truncated curl download cannot run a partial installer.
main() {
    set -Eeuo pipefail
    umask 077
    bootstrap_fail() { printf 'Cina download: %s\n' "$*" >&2; exit 1; }
    bootstrap_help() {
        cat <<'HELP'
Cina Home remote installer

  curl -fsSL https://raw.githubusercontent.com/Matt-Mc/Cina/main/companion/bootstrap.sh | bash

Bootstrap options:
  --dir PATH       Permanent installation directory (default ~/.local/share/cina-home)
  --ref REF        GitHub branch, tag or full commit SHA (default main)
  --repo OWNER/REPO  Source repository (default Matt-Mc/Cina)
  --install-docker  Install missing Docker/Compose on supported Ubuntu/Debian hosts
  --help           Show this help without downloading anything

Installer options are forwarded: --host IP, --port PORT, --cpu, --gpu, --yes,
and --timeout SECONDS. Use --yes for headless operation; it does not itself
authorize system package installation. GPU mode needs configured NVIDIA support.

Existing models, identity and jobs live in named Docker volumes. Reruns retain
.env and accelerator settings. The complete bundle is downloaded before local
code is updated. No git checkout, Python installation or manual unzip is needed.
HELP
    }
    repo='Matt-Mc/Cina' ref='main' destination='' install_docker=0 affirmative=0
    forwarded=()
    while (($#)); do
        case "$1" in
            --dir|--ref|--repo)
                (($# >= 2)) && [[ -n "$2" && "$2" != --* ]] || bootstrap_fail "$1 needs a value."
                case "$1" in --dir) destination=$2 ;; --ref) ref=$2 ;; --repo) repo=$2 ;; esac
                shift 2 ;;
            --install-docker) install_docker=1; shift ;;
            --host|--port|--timeout)
                (($# >= 2)) && [[ -n "$2" && "$2" != --* ]] || bootstrap_fail "$1 needs a value."
                forwarded+=("$1" "$2"); shift 2 ;;
            --cpu|--gpu|--yes)
                [[ "$1" != --yes ]] || affirmative=1
                forwarded+=("$1"); shift ;;
            -h|--help) bootstrap_help; return 0 ;;
            *) bootstrap_fail "Unknown option: $1. Use --help for usage." ;;
        esac
    done
    [[ "$(uname -s)" == Linux ]] || bootstrap_fail 'Run this on the Linux Docker host (or WSL with Docker configured).'
    [[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ && "$repo" != *'..'* ]] || bootstrap_fail 'Repository must be OWNER/REPO.'
    [[ "$ref" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ && "$ref" != *'..'* && "$ref" != *'//'* ]] || bootstrap_fail 'Invalid source branch, tag or SHA.'
    command -v curl >/dev/null 2>&1 || bootstrap_fail 'curl is required. Install curl and ca-certificates with your system package manager.'
    if [[ -n "${DOCKER_HOST:-}" && "${DOCKER_HOST}" != unix://* ]]; then bootstrap_fail 'Run the installer on the actual Docker host, using a local Unix socket.'; fi
    if command -v docker >/dev/null 2>&1; then
        endpoint=$(docker context inspect ${DOCKER_CONTEXT:+"$DOCKER_CONTEXT"} --format '{{.Endpoints.docker.Host}}') || bootstrap_fail 'Could not inspect the Docker context.'
        [[ -z "${DOCKER_HOST:-}" || -n "${DOCKER_CONTEXT:-}" ]] || endpoint=$DOCKER_HOST
        [[ "$endpoint" == unix://* ]] || bootstrap_fail 'Run the installer on the actual Docker host, using a local Unix socket.'
    fi
    [[ -n "$destination" ]] || destination="${XDG_DATA_HOME:-$HOME/.local/share}/cina-home"
    [[ "$destination" != / && ! -L "$destination" ]] || bootstrap_fail 'Choose a dedicated installation directory, not / or a symbolic link.'
    if [[ -e "$destination" ]]; then
        [[ -d "$destination" && -w "$destination" ]] || bootstrap_fail 'The installation directory must be a writable directory you own.'
        if [[ -n "$(ls -A -- "$destination")" ]]; then
            [[ -f "$destination/cina.py" && -f "$destination/compose.yaml" && -f "$destination/install.sh" ]] || bootstrap_fail 'The selected directory contains unrelated files. Choose an empty directory or an existing Cina Home installation.'
        fi
    fi

    # A curl pipe owns stdin. Use a separate terminal descriptor for questions and the child.
    terminal=0
    if ((affirmative == 0)); then
        if { exec 3<>/dev/tty; } 2>/dev/null; then terminal=1; else
            bootstrap_fail 'No terminal is available. Rerun with --yes --host YOUR_SERVER_IP (and --install-docker if needed).'
        fi
    fi
    mkdir -p -- "$destination"
    destination=$(cd -- "$destination" && pwd -P)
    [[ "$destination" != / ]] || bootstrap_fail 'Choose a dedicated installation directory.'
    mkdir "$destination/.cina-bootstrap.lock" 2>/dev/null || bootstrap_fail 'Another bootstrap is running, or .cina-bootstrap.lock remains from an interrupted run. Check before removing that empty directory.'
    staging='' pending_file=''
    files=(bootstrap.sh install.sh setup-docker.sh VERSION cina.py Dockerfile compose.yaml compose.gpu.yaml .env.example .dockerignore README.md VALIDATION.md)
    bootstrap_cleanup() {
        local name
        [[ -z "$pending_file" ]] || rm -f -- "$pending_file"
        if [[ -n "$staging" ]]; then
            for name in "${files[@]}"; do rm -f -- "$staging/$name"; done
            rmdir -- "$staging" 2>/dev/null || true
        fi
        rmdir -- "$destination/.cina-bootstrap.lock" 2>/dev/null || true
    }
    trap bootstrap_cleanup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    [[ ! -e "$destination/.cina-install.lock" ]] || bootstrap_fail 'The local Cina installer is already running. Wait for it to finish.'
    staging=$(mktemp -d "${TMPDIR:-/tmp}/cina-download.XXXXXX")
    curl_options=(-q --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --tlsv1.2 --connect-timeout 15 --max-time 120 --retry 2 --max-filesize 1048576)
    if [[ "$ref" =~ ^[0-9a-fA-F]{40}$ ]]; then revision=${ref,,}; else
        encoded_ref=${ref//\//%2F}
        revision=$(curl "${curl_options[@]}" -H 'Accept: application/vnd.github.sha' "https://api.github.com/repos/$repo/commits/$encoded_ref") || bootstrap_fail 'Could not resolve the GitHub revision. Check the repository/ref, network and GitHub API rate limit.'
        revision=${revision//$'\r'/}
    fi
    [[ "$revision" =~ ^[0-9a-f]{40}$ ]] || bootstrap_fail 'GitHub did not return a full commit SHA. No installed code was changed.'
    printf 'Downloading Cina Home from %s at %s...\n' "$repo" "$revision"
    for name in "${files[@]}"; do
        curl "${curl_options[@]}" --output "$staging/$name" "https://raw.githubusercontent.com/$repo/$revision/companion/$name" || bootstrap_fail "Could not download $name. This revision may not include the companion yet. Installed files were not changed."
        [[ -s "$staging/$name" ]] || bootstrap_fail "Downloaded $name is empty. Installed files were not changed."
    done
    for name in bootstrap.sh install.sh setup-docker.sh; do bash -n "$staging/$name" || bootstrap_fail "Downloaded $name is not a complete Bash script."; done

    # Decide about host-level prerequisites separately from the app's --yes option.
    need_docker=0
    if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then need_docker=1; fi
    if ((need_docker == 1 && install_docker == 0)); then
        if ((terminal == 1)); then
            printf 'Docker/Compose is missing. Install it from Docker\047s official Ubuntu/Debian repository? [y/N] ' >&3
            IFS= read -r reply <&3
            case "$reply" in y|Y|yes|YES) install_docker=1 ;; *) bootstrap_fail 'Install Docker/Compose first, then rerun. No system packages were changed.' ;; esac
        else bootstrap_fail 'Docker/Compose is missing. Use --install-docker to authorize installation on Ubuntu/Debian.'; fi
    fi
    elevate=()
    if ((need_docker == 1)); then
        if ((EUID != 0)); then
            command -v sudo >/dev/null 2>&1 || bootstrap_fail 'sudo is required to install Docker; ask the host administrator to install Docker/Compose.'
            elevate=(sudo)
        fi
        "${elevate[@]}" bash "$staging/setup-docker.sh"
    fi

    # All downloads and prerequisite checks succeeded; atomically replace each managed file.
    for name in "${files[@]}" .cina-source; do
        [[ ! -L "$destination/$name" && ! -d "$destination/$name" ]] || bootstrap_fail "Managed file $name must not be a directory or symbolic link."
    done
    for name in "${files[@]}"; do
        pending_file=$(mktemp "$destination/.cina-download.XXXXXX")
        cp -- "$staging/$name" "$pending_file"
        case "$name" in *.sh) chmod 700 "$pending_file" ;; *) chmod 600 "$pending_file" ;; esac
        mv -f -- "$pending_file" "$destination/$name"
        pending_file=''
    done
    pending_file=$(mktemp "$destination/.cina-download.XXXXXX")
    printf '%s\n%s\n' "$repo" "$revision" > "$pending_file"
    mv -f -- "$pending_file" "$destination/.cina-source"
    pending_file=''
    printf 'Installed source in %s\n' "$destination"

    # A newly installed rootful Docker engine normally requires sudo until group access is configured.
    run_installer=(bash "$destination/install.sh")
    if ! docker info >/dev/null 2>&1 && ((need_docker == 1 && EUID != 0)); then
        [[ -z "${DOCKER_HOST:-}${DOCKER_CONTEXT:-}" ]] || bootstrap_fail 'Docker was installed, but the selected context is unavailable. Select the local engine and rerun.'
        run_installer=(sudo bash "$destination/install.sh")
    fi
    if ((terminal == 1)); then "${run_installer[@]}" "${forwarded[@]}" <&3; else "${run_installer[@]}" "${forwarded[@]}" </dev/null; fi
}

main "$@"
