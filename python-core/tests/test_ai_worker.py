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

    written = worker.write_output_data("req1", [b"result"], None)
    assert written == "6"
    assert read_output_file(tmp_path, "req1") == b"result"


def test_output_larger_than_shared_memory_falls_back_to_file(worker, tmp_path):
    assert worker.write_output_data("req2", [b"x" * 5, b"x" * 6], ("unused_shm", 10)) == "11 FILE"
    assert read_output_file(tmp_path, "req2") == b"x" * 11


def test_shared_memory_round_trip_through_daemon(tmp_path, module_dir):
    from multiprocessing import shared_memory
    work_dir = tmp_path / "work"
    work_dir.mkdir()
    shm_in = shared_memory.SharedMemory(create=True, size=4096)
    shm_out = shared_memory.SharedMemory(create=True, size=8)
    proc = start_daemon(work_dir, module_dir)
    try:
        assert proc.stdout.readline().strip() == "READY"
        shm_in.buf[:2] = b"hi"
        proc.stdin.write(f"EXECUTE SHOUT r1 SHMEM {shm_in.name} 2 {shm_out.name} 8\n")
        proc.stdin.flush()
        assert proc.stdout.readline().strip() == "DONE 2"
        assert bytes(shm_out.buf[:2]) == b"HI"

        shm_in.buf[:10] = b"abcdefghij"
        proc.stdin.write(f"EXECUTE SHOUT r2 SHMEM {shm_in.name} 10 {shm_out.name} 8\n")
        proc.stdin.flush()
        assert proc.stdout.readline().strip() == "DONE 10 FILE"
        assert read_output_file(work_dir, "r2") == b"ABCDEFGHIJ"
        stop(proc)
        assert "leaked shared_memory" not in proc.stderr.read()
    finally:
        if proc.poll() is None:
            proc.kill()
        shm_in.close(); shm_in.unlink(); shm_out.close(); shm_out.unlink()


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
class Vec(list):
    def tolist(self):
        return list(self)
class Box:
    xyxy = [Vec([10.0, 20.0, 110.0, 220.0])]
    cls = [0]
    conf = [0.9]
class Result:
    names = {0: "bus"}
    boxes = [Box()]
class YOLO:
    def __init__(self, path):
        pass
    def to(self, device):
        pass
    def __call__(self, image, conf, verbose):
        return [Result()]
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

        import cv2
        import numpy as np
        _, png = cv2.imencode(".png", np.zeros((640, 1280, 3), dtype=np.uint8))
        assert execute(proc, tmp_path, "YOLO", "r3", png.tobytes()).startswith("DONE ")
        detections = json.loads(read_output_file(tmp_path, "r3"))["detections"]
        assert detections == [{"bbox": [20.0, 40.0, 200.0, 400.0], "label": "bus", "score": 0.9}]
        stop(proc)
    finally:
        if proc.poll() is None:
            proc.kill()


ARRAY_MODULE = '''
import numpy
from jpyrust import task


@task("SCALE")
def scale(data, args):
    return data * float(args)


@task("DESCRIBE")
def describe(data, args):
    return {"type": type(data).__name__, "dtype": str(data.dtype), "shape": list(data.shape)}
'''


@pytest.mark.parametrize("array", [
    __import__("numpy").arange(6, dtype="float32").reshape(2, 3),
    __import__("numpy").array([[1, 2], [3, 4]], dtype=">i4").T,
    __import__("numpy").array(7.5),
    __import__("numpy").zeros((0, 3), dtype="uint8"),
    __import__("numpy").array([True, False]),
])
def test_arrays_round_trip_through_npy(worker, array):
    import io
    import numpy
    encoded = b"".join(bytes(part) for part in worker.encode_array(array))
    assert encoded.startswith(b"\x93NUMPY")
    assert (10 + int.from_bytes(encoded[8:10], "little")) % 64 == 0
    loaded = numpy.load(io.BytesIO(encoded))
    numpy.testing.assert_array_equal(loaded, array)
    decoded = worker.decode_input(encoded)
    numpy.testing.assert_array_equal(decoded, array)
    assert decoded.dtype.byteorder in "<|=" and decoded.shape == array.shape


def test_npy_written_by_numpy_is_decoded_without_copy(worker):
    import io
    import numpy
    stream = io.BytesIO()
    numpy.save(stream, numpy.arange(4, dtype="float64"))
    data = stream.getvalue()
    decoded = worker.decode_input(data)
    assert decoded.base is not None and not decoded.flags.writeable
    numpy.testing.assert_array_equal(decoded, [0, 1, 2, 3])
    assert worker.decode_input(b"plain bytes") == b"plain bytes"


def test_object_arrays_are_rejected(worker):
    import numpy
    with pytest.raises(TypeError):
        worker.encode_array(numpy.array([{"a": 1}], dtype=object))


def test_array_tasks_through_daemon(tmp_path):
    import io
    import numpy
    module_path = tmp_path / "array_modules"
    module_path.mkdir()
    (module_path / "array_tasks.py").write_text(ARRAY_MODULE, encoding="utf-8")
    work_dir = tmp_path / "work"
    work_dir.mkdir()
    stream = io.BytesIO()
    numpy.save(stream, numpy.arange(6, dtype="float32").reshape(3, 2))
    proc = start_daemon(work_dir, module_path)
    try:
        assert proc.stdout.readline().strip() == "READY"
        assert execute(proc, work_dir, "SCALE", "a1", stream.getvalue(), "2").startswith("DONE ")
        result = numpy.load(io.BytesIO(read_output_file(work_dir, "a1")))
        numpy.testing.assert_array_equal(result, numpy.arange(6, dtype="float32").reshape(3, 2) * 2)
        assert result.dtype == numpy.float32
        assert execute(proc, work_dir, "DESCRIBE", "a2", stream.getvalue()).startswith("DONE ")
        assert json.loads(read_output_file(work_dir, "a2")) == {"type": "ndarray", "dtype": "float32", "shape": [3, 2]}
        stop(proc)
    finally:
        if proc.poll() is None:
            proc.kill()


STREAM_MODULE = '''
import os
from jpyrust import task


@task("COUNT")
def count(data, args):
    try:
        for i in range(int(args)):
            yield f"tick {i}"
    finally:
        open(os.path.join(os.path.dirname(__file__), "closed.txt"), "w").write("closed")


@task("HALFWAY")
def halfway(data, args):
    yield "first"
    raise RuntimeError("broke halfway")


@task("PLAIN")
def plain(data, args):
    return data.upper()
'''


def read_line(proc):
    return proc.stdout.readline().strip()


def test_streaming_through_daemon(tmp_path):
    module_path = tmp_path / "stream_modules"
    module_path.mkdir()
    (module_path / "stream_tasks.py").write_text(STREAM_MODULE, encoding="utf-8")
    work_dir = tmp_path / "work"
    work_dir.mkdir()
    proc = start_daemon(work_dir, module_path)
    try:
        assert read_line(proc) == "READY"

        write_input_file(work_dir, "s1", b"")
        proc.stdin.write("STREAM COUNT s1 3\n")
        proc.stdin.flush()
        chunks = []
        while True:
            line = read_line(proc)
            if line == "END":
                break
            assert line.startswith("CHUNK ")
            chunks.append(read_output_file(work_dir, "s1"))
            proc.stdin.write("NEXT\n")
            proc.stdin.flush()
        assert chunks == [b"tick 0", b"tick 1", b"tick 2"]

        (module_path / "closed.txt").unlink()
        write_input_file(work_dir, "s2", b"")
        proc.stdin.write("STREAM COUNT s2 1000000\n")
        proc.stdin.flush()
        assert read_line(proc).startswith("CHUNK ")
        proc.stdin.write("CANCEL\n")
        proc.stdin.flush()
        assert read_line(proc) == "END"
        assert (module_path / "closed.txt").read_text() == "closed"

        write_input_file(work_dir, "s3", b"")
        proc.stdin.write("STREAM HALFWAY s3\n")
        proc.stdin.flush()
        assert read_line(proc).startswith("CHUNK ")
        proc.stdin.write("NEXT\n")
        proc.stdin.flush()
        assert read_line(proc) == "ERROR RuntimeError: broke halfway"

        write_input_file(work_dir, "s4", b"abc")
        proc.stdin.write("STREAM PLAIN s4\n")
        proc.stdin.flush()
        assert read_line(proc) == "CHUNK 3"
        assert read_output_file(work_dir, "s4") == b"ABC"
        proc.stdin.write("NEXT\n")
        proc.stdin.flush()
        assert read_line(proc) == "END"

        assert execute(proc, work_dir, "COUNT", "s5", b"", "2") == \
            "ERROR TypeError: the task yields results; call it with callStream"
        assert execute(proc, work_dir, "PLAIN", "s6", b"ok").startswith("DONE ")

        write_input_file(work_dir, "s7", b"")
        proc.stdin.write("STREAM STATUS s7 NONE\n")
        proc.stdin.flush()
        assert read_line(proc) == "ERROR Task STATUS cannot be streamed"
        stop(proc)
    finally:
        if proc.poll() is None:
            proc.kill()
