"""Pipe the real bootstrap into Bash with fake HTTPS/Docker; no host provisioning."""
import os
from pathlib import Path
import subprocess
import unittest

import test_install as fixture


CURL = r'''#!/bin/bash
set -eu
printf '%q ' "$@" >> "$MOCK_DOWNLOAD_LOG"; printf '\n' >> "$MOCK_DOWNLOAD_LOG"
output='' url=''
while (($#)); do
  case "$1" in
    --output) output=$2; shift 2 ;;
    https://*) url=$1; shift ;;
    *) shift ;;
  esac
done
if [[ "$url" == https://api.github.com/* ]]; then printf '%s\n' "${MOCK_SHA:-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}"; exit 0; fi
name=${url##*/}
if [[ "$name" == "${MOCK_FAIL_DOWNLOAD:-}" ]]; then printf 'partial' > "$output"; exit 22; fi
if [[ "$name" == setup-docker.sh && "${MOCK_STUB_DOCKER_SETUP:-0}" == 1 ]]; then
  cp "$MOCK_SETUP_HELPER" "$output"
else
  cp "$MOCK_SOURCE/$name" "$output"
fi
'''


@unittest.skipUnless(fixture.BASH and Path(fixture.BASH).exists(), "Bash is required")
class BootstrapTests(unittest.TestCase):
    command = fixture.InstallerTests.command
    commands = fixture.InstallerTests.commands

    def setUp(self):
        fixture.InstallerTests.setUp(self)
        self.destination = self.root / "installed companion"
        self.download_log = self.root / "downloads.log"
        self.command("curl", CURL)
        # Compose can be absent until the optional provisioning helper has run.
        docker = fixture.DOCKER.replace('version) exit "${MOCK_COMPOSE_FAIL:-0}" ;;', '''version)
    if [[ "${MOCK_NEEDS_DOCKER:-0}" == 1 && ! -e "$MOCK_DOCKER_READY" ]]; then exit 1; fi
    exit "${MOCK_COMPOSE_FAIL:-0}" ;;''')
        self.command("docker", docker)
        self.command("sudo", '#!/bin/bash\nprintf "sudo %s\\n" "$*" >> "$MOCK_LOG"\nexec "$@"\n')
        helper = self.root / "fake-setup-docker.sh"
        helper.write_bytes(b'#!/bin/bash\nprintf "docker-provisioned\\n" >> "$MOCK_LOG"\nprintf ready > "$MOCK_DOCKER_READY"\n')
        self.env.update(MOCK_SOURCE=fixture.SOURCE.as_posix(), MOCK_DOWNLOAD_LOG=self.download_log.as_posix(),
                        MOCK_SETUP_HELPER=helper.as_posix(), MOCK_DOCKER_READY=(self.root / "docker-ready").as_posix(),
                        TMPDIR=self.root.as_posix())

    def tearDown(self):
        fixture.InstallerTests.tearDown(self)

    def run_bootstrap(self, *args, body=None, **env):
        script = body if body is not None else (fixture.SOURCE / "bootstrap.sh").read_text()
        return subprocess.run([fixture.BASH, "-s", "--", "--dir", self.destination.as_posix(), *args],
                              input=script, env=dict(self.env, **env), cwd=self.root, text=True,
                              capture_output=True, timeout=40, encoding="utf-8", errors="replace",
                              start_new_session=os.name != "nt")

    def test_piped_install_downloads_one_revision_and_runs_full_installer(self):
        result = self.run_bootstrap("--yes", "--cpu", "--host", "192.168.50.20")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('"url":"https://192.168.50.20:8443"', result.stdout)
        self.assertEqual(["Matt-Mc/Cina", "a" * 40], (self.destination / ".cina-source").read_text().splitlines())
        log = self.download_log.read_text()
        self.assertIn("application/vnd.github.sha", log)
        raw_urls = [line for line in log.splitlines() if "raw.githubusercontent.com" in line]
        self.assertEqual(12, len(raw_urls))
        self.assertTrue(all("/" + "a" * 40 + "/companion/" in line for line in raw_urls))
        self.assertFalse((self.destination / ".cina-bootstrap.lock").exists())
        self.assertEqual([], list(self.root.glob("cina-download.*")))

    def test_full_sha_does_not_need_github_api(self):
        result = self.run_bootstrap("--yes", "--cpu", "--ref", "B" * 40)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("api.github.com", self.download_log.read_text())
        self.assertIn("/" + "b" * 40 + "/companion/", self.download_log.read_text())

    def test_ref_with_slash_is_encoded_once_and_all_downloads_pin_sha(self):
        result = self.run_bootstrap("--yes", "--cpu", "--ref", "codex/companion-bootstrap")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("commits/codex%2Fcompanion-bootstrap", self.download_log.read_text())

    def test_rerun_preserves_user_settings_without_deleting_volumes(self):
        first = self.run_bootstrap("--yes", "--cpu")
        self.assertEqual(0, first.returncode, first.stderr)
        config = self.destination / ".env"
        config.write_bytes(config.read_bytes().replace(b"CINA_CONTEXT=4096", b"CINA_CONTEXT=8192"))
        before = config.read_bytes()
        again = self.run_bootstrap("--yes")
        self.assertEqual(0, again.returncode, again.stderr)
        self.assertEqual(before, config.read_bytes())
        self.assertNotIn("down", self.commands())
        self.assertNotIn("volume rm", self.commands())

    def test_download_failure_leaves_existing_code_and_config_unchanged(self):
        first = self.run_bootstrap("--yes", "--cpu")
        self.assertEqual(0, first.returncode, first.stderr)
        before = {file.name: file.read_bytes() for file in self.destination.iterdir() if file.is_file()}
        result = self.run_bootstrap("--yes", MOCK_FAIL_DOWNLOAD="compose.gpu.yaml")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Installed files were not changed", result.stderr)
        self.assertEqual(before, {file.name: file.read_bytes() for file in self.destination.iterdir() if file.is_file()})
        self.assertFalse((self.destination / ".cina-bootstrap.lock").exists())

    def test_bad_sha_and_invalid_options_never_apply_code(self):
        for args, env in [((), {"MOCK_SHA": "not-a-sha"}), (("--ref", "../../other"), {}),
                          (("--repo", "https://evil.example/repo"), {}), (("--missing",), {})]:
            with self.subTest(args=args, env=env):
                result = self.run_bootstrap("--yes", *args, **env)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((self.destination / "install.sh").exists())

    def test_help_does_not_contact_github_or_docker(self):
        result = self.run_bootstrap("--help")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--install-docker", result.stdout)
        self.assertFalse(self.download_log.exists())
        self.assertFalse(self.destination.exists())

    def test_truncated_curl_response_cannot_execute_partial_setup(self):
        script = (fixture.SOURCE / "bootstrap.sh").read_text()
        for body in (script[:len(script) // 2], script[:script.rfind('main "$@"')]):
            result = self.run_bootstrap("--yes", body=body)
            self.assertFalse(self.download_log.exists())
            self.assertFalse(self.destination.exists())

    def test_unrelated_directory_is_never_overwritten(self):
        self.destination.mkdir()
        (self.destination / "personal-file").write_text("Keep this")
        result = self.run_bootstrap("--yes")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unrelated files", result.stderr)
        self.assertFalse(self.download_log.exists())
        self.assertEqual("Keep this", (self.destination / "personal-file").read_text())

    def test_headless_yes_does_not_authorize_host_package_installation(self):
        result = self.run_bootstrap("--yes", MOCK_NEEDS_DOCKER="1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("--install-docker", result.stderr)
        self.assertNotIn("docker-provisioned", self.commands())
        self.assertFalse((self.destination / "install.sh").exists())

    def test_explicit_package_permission_runs_helper_then_companion(self):
        result = self.run_bootstrap("--yes", "--install-docker", "--cpu", MOCK_NEEDS_DOCKER="1", MOCK_STUB_DOCKER_SETUP="1")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("docker-provisioned", self.commands())
        self.assertIn("up -d --build", self.commands())

    def test_remote_docker_context_is_rejected_before_host_changes(self):
        result = self.run_bootstrap("--yes", "--install-docker", MOCK_ENDPOINT="ssh://elsewhere")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.download_log.exists())
        self.assertNotIn("docker-provisioned", self.commands())

    @unittest.skipUnless(os.name == "posix", "Controlling-terminal test runs on Linux CI")
    def test_pipe_keeps_interactive_prompts_on_the_terminal(self):
        import fcntl
        import termios
        master, slave = os.openpty()
        def terminal_session():
            os.setsid()
            fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
        try:
            process = subprocess.Popen([fixture.BASH, "-s", "--", "--dir", self.destination.as_posix(), "--cpu"],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=self.root,
                env=self.env, preexec_fn=terminal_session, pass_fds=(slave,))
            os.write(master, b"\n\n")
            output, error = process.communicate((fixture.SOURCE / "bootstrap.sh").read_bytes(), timeout=40)
            self.assertEqual(0, process.returncode, error.decode())
            self.assertIn(b'"code":"test-only"', output)
        finally:
            os.close(master); os.close(slave)


if __name__ == "__main__":
    unittest.main()
