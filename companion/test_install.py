"""Execute the installer with fake Docker/network commands; never modify the host."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parent
BASH = os.environ.get("CINA_TEST_BASH") or (
    r"C:\Program Files\Git\usr\bin\bash.exe" if os.name == "nt" else shutil.which("bash"))

DOCKER = r'''#!/bin/bash
set -eu
printf '%q ' "$@" >> "$MOCK_LOG"; printf '\n' >> "$MOCK_LOG"
case "${1:-}" in
  context) printf '%s\n' "${MOCK_ENDPOINT:-unix:///var/run/docker.sock}"; exit 0 ;;
  info) exit "${MOCK_INFO_FAIL:-0}" ;;
  run) exit "${MOCK_GPU_DOCKER_FAIL:-0}" ;;
  compose) shift ;;
  *) exit 90 ;;
esac
envfile=.env
while (($#)); do
  case "$1" in
    --env-file) envfile=$2; shift 2 ;;
    --project-name|-f) shift 2 ;;
    *) break ;;
  esac
done
case "${1:-}" in
  version) exit "${MOCK_COMPOSE_FAIL:-0}" ;;
  config)
    if [[ "${2:-}" == --environment ]]; then
      awk '/^[A-Z_]+=/ {sub(/\r$/, ""); print}' "$envfile"
      if [[ -n "${CINA_BIND_IP:-}" ]]; then printf 'CINA_BIND_IP=%s\n' "$CINA_BIND_IP"; fi
    fi
    exit "${MOCK_CONFIG_FAIL:-0}" ;;
  up) exit "${MOCK_UP_FAIL:-0}" ;;
  ps) exit 0 ;;
  exec)
    if [[ "$*" == *'cina.py pair --url '* ]]; then
      [[ "${MOCK_PAIR_FAIL:-0}" == 0 ]] || exit 1
      printf '{"url":"%s","code":"test-only","fingerprint":"fixture","server_id":"fixture"}\n' "${!#}"
      exit 0
    fi
    exit "${MOCK_READY_FAIL:-0}" ;;
  *) exit 91 ;;
esac
'''


@unittest.skipUnless(BASH and Path(BASH).exists(), "Bash is required for installer tests")
class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="cina-installer-")
        self.root = Path(self.temp.name)
        self.bundle = self.root / "bundle with spaces"
        self.bundle.mkdir()
        for name in ["install.sh", "compose.yaml", "compose.gpu.yaml", "Dockerfile", "cina.py", ".env.example"]:
            (self.bundle / name).write_bytes((SOURCE / name).read_bytes())
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.command("docker", DOCKER)
        self.command("uname", '#!/bin/bash\nprintf "%s\\n" "${MOCK_OS:-Linux}"\n')
        self.command("nvidia-smi", '#!/bin/bash\nexit "${MOCK_GPU_HOST:-1}"\n')
        self.command("ip", '''#!/bin/bash
if [[ "$*" == *'route get'* ]]; then
  printf '1.1.1.1 via 192.168.50.1 dev eth0 src %s uid 1000\n' "${MOCK_HOST:-192.168.50.20}"
else
  printf '2: eth0 inet %s/24 brd 192.168.50.255 scope global eth0\n' "${MOCK_HOST:-192.168.50.20}"
fi
''')
        self.command("sleep", '#!/bin/bash\n/bin/sleep 0.1\n')
        self.log = self.root / "commands.log"
        self.env = {key: value for key, value in os.environ.items()
                    if key.upper() not in {"PATH", "DOCKER_HOST", "DOCKER_CONTEXT"} and not key.startswith("CINA_")}
        path = os.environ.get("PATH", os.environ.get("Path", ""))
        if os.name == "nt":
            path = str(Path(BASH).parent) + os.pathsep + path
        self.env.update(PATH=str(self.bin) + os.pathsep + path, MOCK_LOG=self.log.as_posix())

    def tearDown(self):
        self.temp.cleanup()

    def command(self, name, text):
        file = self.bin / name
        file.write_bytes(text.encode())
        file.chmod(0o755)

    def run_install(self, *args, **env):
        return subprocess.run([BASH, str(self.bundle / "install.sh").replace("\\", "/"), *args],
                              cwd=self.root, env=dict(self.env, **env), input="", text=True,
                              capture_output=True, timeout=30, encoding="utf-8", errors="replace")

    def commands(self):
        return self.log.read_text() if self.log.exists() else ""

    def test_syntax_and_help_need_no_docker(self):
        result = subprocess.run([BASH, "-n", (SOURCE / "install.sh").as_posix()], capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        result = self.run_install("--help")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--gpu", result.stdout)
        self.assertFalse(self.log.exists())

    def test_cpu_install_autodetects_private_address_and_prints_pairing(self):
        result = self.run_install("--yes")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("CINA_BIND_IP=192.168.50.20", (self.bundle / ".env").read_text())
        self.assertIn('"url":"https://192.168.50.20:8443"', result.stdout)
        self.assertEqual("cpu", (self.bundle / ".cina-install-mode").read_text().strip())
        self.assertIn("up -d --build", self.commands())
        self.assertIn("/api/tags", self.commands())
        self.assertIn("error.code==401", self.commands())
        self.assertNotIn("--gpus", self.commands())
        self.assertFalse((self.bundle / ".cina-install.lock").exists())

    def test_existing_settings_and_mode_survive_rerun_without_executing_dotenv(self):
        marker = self.root / "should-not-exist"
        original = f'CINA_BIND_IP=192.168.50.42\nCINA_PORT=9443\nCINA_CONTEXT=8192\nCINA_NAME=$(touch "{marker.as_posix()}")\nCINA_OLLAMA_IMAGE=approved-image@sha256:abc\n'
        (self.bundle / ".env").write_text(original)
        (self.bundle / ".cina-install-mode").write_text("cpu\n")
        result = self.run_install("--yes", MOCK_GPU_HOST="0")
        self.assertEqual(0, result.returncode, result.stderr)
        after = (self.bundle / ".env").read_text()
        self.assertIn("CINA_BIND_IP=192.168.50.42", after)
        self.assertIn("CINA_PORT=9443", after)
        self.assertIn("CINA_CONTEXT=8192", after)
        self.assertIn('CINA_NAME=$(touch', after)
        self.assertFalse(marker.exists())
        self.assertNotIn("--gpus", self.commands())
        self.assertNotIn("down", self.commands())
        self.assertNotIn("volume rm", self.commands())

    def test_gpu_probe_and_override_are_used(self):
        result = self.run_install("--yes", MOCK_GPU_HOST="0")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("run --rm --gpus all --entrypoint nvidia-smi --", self.commands())
        self.assertIn("-f compose.gpu.yaml up -d --build", self.commands())
        self.assertEqual("gpu", (self.bundle / ".cina-install-mode").read_text().strip())

    def test_repeating_setup_keeps_identical_settings(self):
        first = self.run_install("--yes", "--cpu")
        self.assertEqual(0, first.returncode, first.stderr)
        before = (self.bundle / ".env").read_bytes()
        second = self.run_install("--yes")
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertEqual(before, (self.bundle / ".env").read_bytes())

    def test_failed_gpu_probe_never_silently_falls_back_or_changes_settings(self):
        result = self.run_install("--yes", "--gpu", MOCK_GPU_DOCKER_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Container Toolkit", result.stderr)
        self.assertNotIn("up -d", self.commands())
        self.assertFalse((self.bundle / ".env").exists())
        self.assertFalse((self.bundle / ".cina-install.lock").exists())

    def test_explicit_cpu_skips_gpu_even_if_detected(self):
        result = self.run_install("--yes", "--cpu", "--host", "100.80.1.2", "--port", "9443", MOCK_GPU_HOST="0")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("--gpus", self.commands())
        self.assertIn('"url":"https://100.80.1.2:9443"', result.stdout)

    def test_invalid_address_port_and_arguments_fail_before_start(self):
        for args in [("--host", "0.0.0.0"), ("--host", "127.0.0.1"), ("--host", "192.168.1.999"),
                     ("--host", "192.168.01.2"), ("--host", "$(touch unwanted)"), ("--port", "65536"),
                     ("--port", "0"), ("--timeout", "0"), ("--gpu", "--cpu"), ("--host",), ("--wrong",)]:
            with self.subTest(args=args):
                result = self.run_install("--yes", *args)
                self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertNotIn("up -d", self.commands())
        self.assertFalse((self.bundle / ".env").exists())

    def test_noninteractive_run_requires_yes_before_writing_settings(self):
        result = self.run_install("--cpu", "--host", "192.168.50.20")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("--yes", result.stderr)
        self.assertFalse((self.bundle / ".env").exists())

    def test_no_private_address_requires_explicit_host(self):
        result = self.run_install("--yes", MOCK_HOST="203.0.113.9")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("--host", result.stderr)
        self.assertNotIn("up -d", self.commands())

    def test_remote_context_and_daemon_failure_do_not_mutate_host(self):
        for env in [{"MOCK_ENDPOINT": "ssh://other-server"}, {"MOCK_INFO_FAIL": "1"}, {"MOCK_COMPOSE_FAIL": "1"}]:
            result = self.run_install("--yes", **env)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((self.bundle / ".env").exists())
        self.assertNotIn("up -d", self.commands())

    def test_environment_context_takes_precedence_over_docker_host(self):
        result = self.run_install("--yes", DOCKER_HOST="unix:///var/run/docker.sock", DOCKER_CONTEXT="remote", MOCK_ENDPOINT="ssh://other-server")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("up -d", self.commands())

    def test_readiness_failure_never_prints_pairing_and_keeps_data(self):
        result = self.run_install("--yes", "--timeout", "1", MOCK_READY_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("left in place", result.stderr)
        self.assertNotIn("cina.py pair", self.commands())
        self.assertTrue((self.bundle / ".env").exists())
        self.assertNotIn("down", self.commands())
        self.assertFalse((self.bundle / ".cina-install.lock").exists())

    def test_build_failure_is_not_reported_as_success(self):
        result = self.run_install("--yes", MOCK_UP_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("Cina Home is ready", result.stdout)
        self.assertNotIn("cina.py pair", self.commands())
        self.assertFalse((self.bundle / ".cina-install.lock").exists())

    def test_incomplete_bundle_and_concurrent_install_are_rejected(self):
        (self.bundle / "cina.py").unlink()
        result = self.run_install("--yes")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.log.exists())
        shutil.copyfile(SOURCE / "cina.py", self.bundle / "cina.py")
        (self.bundle / ".cina-install.lock").mkdir()
        result = self.run_install("--yes")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Another installer", result.stderr)
        self.assertNotIn("up -d", self.commands())
        self.assertTrue((self.bundle / ".cina-install.lock").exists())


if __name__ == "__main__":
    unittest.main()
