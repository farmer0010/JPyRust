import importlib.util
import json
import os
import struct
import subprocess
import sys

import pytest

WORKER_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "ai_worker.py")


@pytest.fixture
def worker(tmp_path, monkeypatch):
    monkeypatch.setattr(sys, "argv", ["ai_worker.py", "--work-dir", str(tmp_path)])
    spec = importlib.util.spec_from_file_location("ai_worker_under_test", WORKER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_input_file(work_dir, request_id, payload):
    with open(os.path.join(work_dir, f"input_{request_id}.dat"), "wb") as f:
        f.write(struct.pack(">I", len(payload)))
        f.write(payload)


def read_output_file(work_dir, request_id):
    with open(os.path.join(work_dir, f"output_{request_id}.dat"), "rb") as f:
        length = struct.unpack(">I", f.read(4))[0]
        return f.read(length)


def test_file_protocol_round_trip(worker, tmp_path):
    write_input_file(tmp_path, "req1", b"hello")
    data, metadata, out_info = worker.parse_input_protocol("req1", ["TEXT"])
    assert data == b"hello"
    assert metadata == ["TEXT"]
    assert out_info is None

    written = worker.write_output_data("req1", b"result", None)
    assert written == 6
    assert read_output_file(tmp_path, "req1") == b"result"


def test_output_larger_than_shared_memory_raises(worker):
    with pytest.raises(ValueError, match="Output too large"):
        worker.write_output_data("req2", b"x" * 11, ("unused_shm", 10))


def test_daemon_status_round_trip(tmp_path):
    proc = subprocess.Popen(
        [sys.executable, WORKER_PATH, "--daemon", "--work-dir", str(tmp_path), "--instance-id", "pytest"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        text=True,
    )
    try:
        assert proc.stdout.readline().strip() == "READY"

        write_input_file(tmp_path, "req3", b"")
        proc.stdin.write("EXECUTE STATUS req3 NONE\n")
        proc.stdin.flush()
        response = proc.stdout.readline().strip()
        assert response.startswith("DONE ")

        status = json.loads(read_output_file(tmp_path, "req3"))
        assert status["status"] == "UP"
        assert status["instance"] == "pytest"

        proc.stdin.write("EXECUTE NOPE req4\n")
        proc.stdin.flush()
        assert proc.stdout.readline().strip() == "ERROR Unknown task: NOPE"

        proc.stdin.write("EXIT\n")
        proc.stdin.flush()
        assert proc.wait(timeout=10) == 0
    finally:
        if proc.poll() is None:
            proc.kill()
