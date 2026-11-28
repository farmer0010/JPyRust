import sys
import os
import struct
import time
import multiprocessing.shared_memory
import argparse
import io
import platform
import importlib.util
import glob
import collections.abc
import json
import math

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import jpyrust

if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

APP_START_TIME = time.time()

try:
    import torch
    if torch.cuda.is_available():
        DEVICE = 'cuda'
    elif torch.backends.mps.is_available():
        DEVICE = 'mps'
    else:
        DEVICE = 'cpu'
except ImportError:
    DEVICE = 'cpu'

parser = argparse.ArgumentParser()
parser.add_argument("--daemon", action="store_true")
parser.add_argument("--model", type=str, default="yolov8n.pt")
parser.add_argument("--conf", type=float, default=0.5)
parser.add_argument("--whisper-model", type=str, default="")
parser.add_argument("--mem-key", type=str, default="")
parser.add_argument("--instance-id", type=str, default="default")
parser.add_argument("--work-dir", type=str, default="")
args, unknown = parser.parse_known_args()

WORK_DIR = args.work_dir if args.work_dir else os.path.expanduser(f"~/.jpyrust/{args.instance_id}")
if not os.path.exists(WORK_DIR):
    os.makedirs(WORK_DIR)

EXTRA_TASKS = {
    "YOLO": "vision",
    "EDGE_DETECT": "vision",
    "WHISPER": "audio",
    "NLP_TEXTBLOB": "ml",
    "REGRESSION": "ml",
}

def initialize_models():
    load_user_modules()

def load_user_modules():
    config_path = os.path.join(WORK_DIR, "worker.json")
    if not os.path.exists(config_path):
        return
    with open(config_path, encoding="utf-8") as f:
        worker_config = json.load(f)
    jpyrust.config.update({
        "model": args.model,
        "conf": args.conf,
        "whisper_model": args.whisper_model,
        "device": DEVICE,
    })
    extras_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "extras")
    for extra in worker_config.get("extras", []):
        load_module_file(f"jpyrust_extra_{extra}", os.path.join(extras_dir, f"{extra}.py"))
    for module_dir in worker_config.get("module_dirs", []):
        if module_dir not in sys.path:
            sys.path.insert(0, module_dir)
        for module_file in sorted(glob.glob(os.path.join(module_dir, "*.py"))):
            load_module_file(os.path.splitext(os.path.basename(module_file))[0], module_file)
    for task_name, fn in jpyrust.registered_tasks().items():
        TASK_HANDLERS[task_name] = make_task_handler(fn)

def load_module_file(module_name, module_file):
    try:
        spec = importlib.util.spec_from_file_location(module_name, module_file)
        module = importlib.util.module_from_spec(spec)
        sys.modules[module_name] = module
        spec.loader.exec_module(module)
    except Exception as e:
        sys.modules.pop(module_name, None)
        print(f"[JPyRust-Worker] Failed to load {module_file}: {e}", file=sys.stderr, flush=True)

NPY_MAGIC = b"\x93NUMPY"

def decode_input(data):
    if not data.startswith(NPY_MAGIC):
        return data
    import numpy
    from numpy.lib import format as npy
    stream = io.BytesIO(data)
    version = npy.read_magic(stream)
    read_header = npy.read_array_header_1_0 if version == (1, 0) else npy.read_array_header_2_0
    shape, fortran_order, dtype = read_header(stream)
    array = numpy.frombuffer(data, dtype=dtype, count=math.prod(shape), offset=stream.tell())
    return array.reshape(shape, order="F" if fortran_order else "C")

def encode_array(array):
    import numpy
    from numpy.lib import format as npy
    if array.dtype.hasobject:
        raise TypeError("arrays of Python objects cannot be returned; convert to a numeric dtype")
    if array.dtype.byteorder == ">":
        array = array.astype(array.dtype.newbyteorder("<"))
    if not array.flags.c_contiguous:
        array = array.copy(order="C")
    header = "{'descr': %r, 'fortran_order': False, 'shape': %r, }" % (npy.dtype_to_descr(array.dtype), array.shape)
    header += " " * (63 - (10 + len(header)) % 64) + "\n"
    return [NPY_MAGIC + b"\x01\x00" + struct.pack("<H", len(header)), header.encode("latin1"),
            array.reshape(-1).view(numpy.uint8)]

def to_output_parts(result):
    if result is None:
        return [b""]
    if isinstance(result, (bytes, bytearray, memoryview)):
        return [result]
    if isinstance(result, str):
        return [result.encode("utf-8")]
    numpy = sys.modules.get("numpy")
    if numpy is not None and isinstance(result, numpy.ndarray):
        return encode_array(result)
    return [json.dumps(result, ensure_ascii=False).encode("utf-8")]

def error_line(e):
    return "ERROR " + " ".join(f"{type(e).__name__}: {e}".split())

def is_stream(result):
    return isinstance(result, collections.abc.Iterator)

def make_task_handler(fn):
    def handle(request_id, raw_metadata):
        try:
            data, metadata, out_info = parse_input_protocol(request_id, raw_metadata)
            result = fn(decode_input(data), " ".join(metadata))
            if is_stream(result):
                getattr(result, "close", lambda: None)()
                raise TypeError("the task yields results; call it with callStream")
            written = write_output_data(request_id, to_output_parts(result), out_info)
            return f"DONE {written}"
        except Exception as e:
            return error_line(e)

    def stream(request_id, raw_metadata):
        try:
            data, metadata, out_info = parse_input_protocol(request_id, raw_metadata)
            result = fn(decode_input(data), " ".join(metadata))
            chunks = result if is_stream(result) else iter([result])
            try:
                for chunk in chunks:
                    print(f"CHUNK {write_output_data(request_id, to_output_parts(chunk), out_info)}", flush=True)
                    if sys.stdin.readline().strip().upper() != "NEXT":
                        break
            finally:
                getattr(chunks, "close", lambda: None)()
            return "END"
        except Exception as e:
            return error_line(e)

    handle.stream = stream
    return handle

_shared_memory = {}

def attach_shared_memory(name):
    if name not in _shared_memory:
        shm = multiprocessing.shared_memory.SharedMemory(name=name)
        if sys.platform != "win32":
            from multiprocessing import resource_tracker
            resource_tracker.unregister(shm._name, "shared_memory")
        _shared_memory[name] = shm
    return _shared_memory[name]

def release_shared_memory(keep):
    for name in [n for n in _shared_memory if n not in keep]:
        _shared_memory.pop(name).close()

def parse_input_protocol(request_id, metadata):
    if len(metadata) >= 5 and metadata[0] == "SHMEM":
        in_name, in_size, out_name, out_capacity = metadata[1], int(metadata[2]), metadata[3], int(metadata[4])
        release_shared_memory({in_name, out_name})
        for attempt in range(15):
            try:
                data = bytes(attach_shared_memory(in_name).buf[:in_size])
                return data, metadata[5:], (out_name, out_capacity)
            except Exception:
                time.sleep(0.05 + attempt * 0.01)
        raise RuntimeError("Input SHMEM read failed")

    input_path = os.path.join(WORK_DIR, f"input_{request_id}.dat")
    with open(input_path, "rb") as f:
        length_bytes = f.read(4)
        data_length = struct.unpack(">I", length_bytes)[0]
        data = f.read(data_length)
    return data, metadata, None

def write_output_data(request_id, parts, out_shm_info):
    views = [memoryview(part).cast("B") for part in parts]
    size = sum(view.nbytes for view in views)
    if out_shm_info and size <= out_shm_info[1]:
        try:
            buf = attach_shared_memory(out_shm_info[0]).buf
            position = 0
            for view in views:
                buf[position:position + view.nbytes] = view
                position += view.nbytes
            return str(size)
        except Exception:
            pass
    output_path = os.path.join(WORK_DIR, f"output_{request_id}.dat")
    with open(output_path, "wb") as f:
        f.write(struct.pack(">I", size))
        for view in views:
            f.write(view)
    return f"{size} FILE" if out_shm_info else str(size)

def handle_status(request_id, raw_metadata):
    try:
        status = {
            "status": "UP",
            "uptime": int(time.time() - APP_START_TIME),
            "pid": os.getpid(),
            "device": DEVICE,
            "instance": args.instance_id
        }
        _, _, out_info = parse_input_protocol(request_id, raw_metadata)
        written = write_output_data(request_id, [json.dumps(status).encode('utf-8')], out_info)
        return f"DONE {written}"
    except Exception as e:
        return f"ERROR {e}"

TASK_HANDLERS = {
    "STATUS": handle_status,
}

def daemon_loop():
    print("READY", flush=True)
    while True:
        line = sys.stdin.readline()
        if not line: break
        parts = line.strip().split()
        if not parts: continue
        cmd = parts[0].upper()
        if cmd == "EXIT": break
        if cmd in ("EXECUTE", "STREAM") and len(parts) >= 3:
            task_type, req_id = parts[1].upper(), parts[2]
            handler = TASK_HANDLERS.get(task_type)
            if handler and cmd == "STREAM":
                streamer = getattr(handler, "stream", None)
                print(streamer(req_id, parts[3:]) if streamer else f"ERROR Task {task_type} cannot be streamed", flush=True)
            elif handler: print(handler(req_id, parts[3:]), flush=True)
            elif task_type in EXTRA_TASKS: print(f"ERROR Task {task_type} requires extras(\"{EXTRA_TASKS[task_type]}\")", flush=True)
            else: print(f"ERROR Unknown task: {task_type}", flush=True)

if __name__ == "__main__":
    initialize_models()
    daemon_loop()
