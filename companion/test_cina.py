"""Contract and recovery tests; no model weights or GPU required."""
import json
import ssl
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
import uuid

from cina import Handler, Ollama, Problem, Service, Store, ThreadingHTTPServer


class FakeRunner:
    def __init__(self):
        self.calls = 0
        self.digest = "original-model-digest"
        self.hook = None

    def models(self):
        return [{"name": "qwen3:4b", "bytes": 2500000000, "digest": self.digest, "loaded": False}]

    def execute(self, job, update, stopped, context):
        self.calls += 1
        update("Generating", "Partial")
        if self.hook:
            self.hook(job)
        return "Verified fixture answer"


class ContractTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.store = Store(self.directory.name)
        self.runner = FakeRunner()
        self.service = Service(self.store, self.runner)
        self.service.refresh_inventory()
        pair = self.store.pair({"code": self.store.pair_code(), "name": "test phone"})
        self.owner = pair["device_id"]
        self.token = pair["token"]

    def tearDown(self):
        self.store.db.close()
        self.directory.cleanup()

    def body(self, kind="analysis"):
        return {"id": str(uuid.uuid4()), "model": "qwen3:4b", "kind": kind, "title": "Read a passage", "prompt": "Summarise this text" if kind == "analysis" else ""}

    def test_pairing_is_single_use_expiring_and_revocable(self):
        code = self.store.pair_code()
        self.store.pair({"code": code})
        with self.assertRaises(Problem):
            self.store.pair({"code": code})
        self.assertEqual(self.owner, self.store.authenticate("Bearer " + self.token))
        self.assertNotIn(self.token, str(list(self.store.db.execute("SELECT * FROM devices"))))
        code = self.store.pair_code()
        self.store.set("pair_expires", "0")
        with self.assertRaises(Problem):
            self.store.pair({"code": code})
        with self.store.db:
            self.store.db.execute("DELETE FROM devices WHERE id=?", (self.owner,))
        with self.assertRaises(Problem):
            self.store.authenticate("Bearer " + self.token)

    def test_lost_ack_replay_does_not_execute_twice(self):
        body = self.body()
        self.service.submit(body, self.owner)
        self.service.work_once()
        again = self.service.submit(body, self.owner)
        self.assertEqual("completed", again["state"])
        self.assertFalse(self.service.work_once())
        self.assertEqual(1, self.runner.calls)
        body["prompt"] = "Changed prompt"
        with self.assertRaises(Problem):
            self.service.submit(body, self.owner)

    def test_other_phone_cannot_read_control_or_replace_job(self):
        body = self.body()
        self.service.submit(body, self.owner)
        self.assertEqual([], self.store.jobs("different-phone"))
        with self.assertRaises(Problem):
            self.store.control(body["id"], "cancel", "different-phone")
        with self.assertRaises(Problem):
            self.service.submit(body, "different-phone")

    def test_pause_ack_waits_for_runner_and_resume_restarts(self):
        body = self.body()
        self.service.submit(body, self.owner)
        def pause(job):
            result = self.store.control(job["id"], "pause", self.owner)
            self.assertEqual("pausing", result["state"])
        self.runner.hook = pause
        self.service.work_once()
        self.assertEqual("paused", self.store.jobs()[0]["state"])
        self.runner.hook = None
        self.store.control(body["id"], "resume", self.owner)
        self.service.work_once()
        self.assertEqual("completed", self.store.jobs()[0]["state"])
        self.assertEqual(2, self.runner.calls)

    def test_cancel_during_generation_never_delivers_completion(self):
        body = self.body()
        self.service.submit(body, self.owner)
        self.runner.hook = lambda job: self.store.control(job["id"], "cancel", self.owner)
        self.service.work_once()
        self.assertEqual("cancelled", self.store.jobs()[0]["state"])
        self.store.control(body["id"], "resume", self.owner)
        self.assertFalse(self.service.work_once())

    def test_pending_cancel_tombstone_blocks_delayed_submission(self):
        body = self.body()
        with self.store.lock:
            self.store.submit(body, self.owner, cancelling=True)
            self.store.control(body["id"], "cancel", self.owner)
        self.assertEqual("cancelled", self.service.submit(body, self.owner)["state"])
        self.assertFalse(self.service.work_once())

    def test_restart_preserves_queue_and_requires_explicit_resume(self):
        body = self.body()
        self.service.submit(body, self.owner)
        self.store.change(body["id"], "running", output="Unfinished")
        self.store.db.close()
        self.store = Store(self.directory.name)
        restarted = Service(self.store, self.runner)
        self.assertEqual("interrupted", self.store.jobs()[0]["state"])
        self.assertFalse(restarted.work_once())
        self.store.control(body["id"], "resume", self.owner)
        restarted.work_once()
        self.assertEqual("Verified fixture answer", self.store.jobs()[0]["output"])

    def test_worker_pause_keeps_control_available_and_persists(self):
        self.service.submit(self.body(), self.owner)
        self.store.set("paused", "1")
        self.assertFalse(self.service.work_once())
        self.assertTrue(self.service.state(self.owner)["paused"])
        self.assertEqual("queued", self.store.jobs()[0]["state"])
        self.store.set("paused", "0")
        self.assertTrue(self.service.work_once())

    def test_installed_digest_is_pinned_and_mutations_cannot_race_jobs(self):
        self.service.submit(self.body(), self.owner)
        for operation in ["delete", "download", "unload"]:
            with self.assertRaises(Problem):
                self.service.submit(self.body(operation), self.owner)
        self.runner.digest = "replacement-digest"
        self.service.work_once()
        self.assertEqual("failed", self.store.jobs()[0]["state"])
        self.assertEqual(0, self.runner.calls)

    def test_cloud_names_urls_unknown_fields_and_oversized_prompts_rejected(self):
        for name in ["qwen3:cloud", "https://evil/model", "../../secret", "namespace/model"]:
            body = self.body(); body["model"] = name
            with self.assertRaises(Problem):
                self.store.submit(body, self.owner)
        body = self.body(); body["prompt"] = "a" * 24001
        with self.assertRaises(Problem):
            self.store.submit(body, self.owner)
        body = self.body(); body["shell"] = "something"
        with self.assertRaises(Problem):
            self.store.submit(body, self.owner)

    def test_http_auth_and_job_status_contract(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.service = self.service
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        base = "http://127.0.0.1:" + str(server.server_port)
        def call(path, body=None, token=None):
            return urllib.request.urlopen(urllib.request.Request(base + path, data=json.dumps(body).encode() if body else None,
                headers={"Authorization": "Bearer " + token} if token else {}))
        try:
            with self.assertRaises(urllib.error.HTTPError) as error:
                call("/v1/state")
            self.assertEqual(401, error.exception.code)
            body = self.body()
            with call("/v1/jobs", body, self.token) as result:
                self.assertEqual("queued", json.load(result)["state"])
            with call("/v1/jobs/status", {"ids": [body["id"]]}, self.token) as result:
                self.assertEqual(body["id"], json.load(result)["jobs"][0]["id"])
            with call("/v1/jobs/discard", body, self.token) as result:
                self.assertEqual("cancelled", json.load(result)["state"])
        finally:
            server.shutdown(); server.server_close(); thread.join()


class RunnerProtocolTests(unittest.TestCase):
    def test_model_management_uses_local_runner_contract_and_real_layer_progress(self):
        import io
        class Runner(Ollama):
            def __init__(self):
                self.calls = []
            def models(self):
                return [{"name": "qwen3:4b"}]
            def request(self, path, body=None, method=None):
                self.calls.append((path, body, method))
                return {}
            def open(self, path, body=None, method=None):
                self.calls.append((path, body, method))
                return io.BytesIO(b'{"status":"pulling layer","completed":25,"total":100}\n{"status":"success"}\n')
        runner, progress = Runner(), []
        result = runner.execute({"kind": "download", "model": "qwen3:4b"}, lambda detail, _: progress.append(detail), lambda: False)
        self.assertEqual("Model downloaded.", result)
        self.assertIn("25% of current layer", progress[0])
        self.assertEqual(("/api/pull", {"model": "qwen3:4b", "stream": True}, None), runner.calls[0])
        for kind in ("load", "unload", "delete"):
            runner.execute({"kind": kind, "model": "qwen3:4b"}, lambda *_: None, lambda: False)
        self.assertEqual("5m", runner.calls[1][1]["keep_alive"])
        self.assertEqual(0, runner.calls[2][1]["keep_alive"])
        self.assertEqual(("/api/delete", {"model": "qwen3:4b"}, "DELETE"), runner.calls[3])

    def test_output_limit_is_not_reported_as_a_complete_answer(self):
        import io
        class Runner(Ollama):
            def models(self):
                return [{"name": "qwen3:4b"}]
            def open(self, path, body=None, method=None):
                return io.BytesIO(b'{"response":"unfinished","done":true,"done_reason":"length"}\n')
        with self.assertRaises(Problem):
            Runner("http://unused").execute({"kind": "analysis", "model": "qwen3:4b", "prompt": "hi"}, lambda *_: None, lambda: False)

    def test_stream_requires_completion_and_does_not_invent_success(self):
        import io
        class Runner(Ollama):
            def models(self):
                return [{"name": "qwen3:4b"}]
            def open(self, path, body=None, method=None):
                return io.BytesIO(b'{"response":"partial","done":false}\n')
        runner = Runner("http://unused")
        with self.assertRaises(Problem):
            runner.execute({"kind": "analysis", "model": "qwen3:4b", "prompt": "hi"}, lambda *_: None, lambda: False)


if __name__ == "__main__":
    unittest.main()
