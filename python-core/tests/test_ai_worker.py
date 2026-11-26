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


USER_MODULE = '''
from jpyrust import task


@task("ECHO")
def echo(data, args):
    return {"text": data.decode("utf-8"), "args": args}


@task
def shout(data, args):
    return data.decode("utf-8").upper()


@task("BOOM")
def boom(data, args):
    raise ValueError("bad\\ninput")
'''


def start_daemon(work_dir, module_dir=None, prelude="", extras=()):
    with open(os.path.join(work_dir, "worker.json"), "w", encoding="utf-8") as f:
        json.dump({"extras": list(extras), "module_dirs": [str(module_dir)] if module_dir else []}, f)
    script = (
        prelude
        + "import runpy, sys\n"
        + f"sys.argv = [{WORKER_PATH!r}, '--daemon', '--work-dir', {str(work_dir)!r}]\n"
        + f"runpy.run_path({WORKER_PATH!r}, run_name='__main__')\n"
    )
    return subprocess.Popen(
        [sys.executable, "-c", script],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def execute(proc, work_dir, task, request_id, payload, args=""):
    write_input_file(work_dir, request_id, payload)
    proc.stdin.write(f"EXECUTE {task} {request_id} {args}\n")
    proc.stdin.flush()
    return proc.stdout.readline().strip()


def stop(proc):
    proc.stdin.write("EXIT\n")
    proc.stdin.flush()
    assert proc.wait(timeout=10) == 0


@pytest.fixture
def module_dir(tmp_path):
    path = tmp_path / "modules"
    path.mkdir()
    (path / "user_tasks.py").write_text(USER_MODULE, encoding="utf-8")
    (path / "broken.py").write_text("raise RuntimeError('broken on import')\n", encoding="utf-8")
    return path


def test_registered_tasks_run_through_daemon(tmp_path, module_dir):
    work_dir = tmp_path / "work"
    work_dir.mkdir()
    proc = start_daemon(work_dir, module_dir)
    try:
        assert proc.stdout.readline().strip() == "READY"

        assert execute(proc, work_dir, "ECHO", "r1", "안녕".encode("utf-8"), "a b").startswith("DONE ")
        assert json.loads(read_output_file(work_dir, "r1")) == {"text": "안녕", "args": "a b"}

        assert execute(proc, work_dir, "shout", "r2", b"hi").startswith("DONE ")
        assert read_output_file(work_dir, "r2") == b"HI"

        assert execute(proc, work_dir, "BOOM", "r3", b"") == "ERROR ValueError: bad input"

        stop(proc)
        assert "broken.py" in proc.stderr.read()
    finally:
        if proc.poll() is None:
            proc.kill()


def test_daemon_starts_without_numpy_or_cv2(tmp_path, module_dir):
    work_dir = tmp_path / "work"
    work_dir.mkdir()
    proc = start_daemon(work_dir, module_dir, prelude="import sys\nsys.modules['numpy'] = None\nsys.modules['cv2'] = None\n")
    try:
        assert proc.stdout.readline().strip() == "READY"
        assert execute(proc, work_dir, "SHOUT", "r1", b"ok").startswith("DONE ")
        assert read_output_file(work_dir, "r1") == b"OK"
        stop(proc)
    finally:
        if proc.poll() is None:
            proc.kill()


FAKE_ULTRALYTICS = '''import sys, types
fake = types.ModuleType("ultralytics")
class YOLO:
    names = {0: "bus"}
    def __init__(self, path):
        pass
    def to(self, device):
        pass
fake.YOLO = YOLO
sys.modules["ultralytics"] = fake
'''


def test_extra_tasks_need_their_extra(tmp_path):
    proc = start_daemon(tmp_path)
    try:
        assert proc.stdout.readline().strip() == "READY"
        assert execute(proc, tmp_path, "YOLO", "r1", b"") == 'ERROR Task YOLO requires extras("vision")'
        assert execute(proc, tmp_path, "WHISPER", "r2", b"") == 'ERROR Task WHISPER requires extras("audio")'
        stop(proc)
    finally:
        if proc.poll() is None:
            proc.kill()


def test_vision_extra_registers_edge_detect(tmp_path):
    proc = start_daemon(tmp_path, prelude=FAKE_ULTRALYTICS, extras=["vision"])
    try:
        assert proc.stdout.readline().strip() == "READY"
        pixels = bytes([0, 0, 0, 255, 255, 255] * 8)
        assert execute(proc, tmp_path, "EDGE_DETECT", "r1", pixels, "4 4 3").startswith("DONE ")
        assert read_output_file(tmp_path, "r1")[:2] == b"\xff\xd8"
        assert execute(proc, tmp_path, "YOLO", "r2", b"not an image") == "ERROR ValueError: Failed to decode image"
        stop(proc)
    finally:
        if proc.poll() is None:
            proc.kill()
