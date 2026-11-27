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
import json

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

def to_output_bytes(result):
    if result is None:
        return b""
    if isinstance(result, (bytes, bytearray, memoryview)):
        return bytes(result)
    if isinstance(result, str):
        return result.encode("utf-8")
    return json.dumps(result, ensure_ascii=False).encode("utf-8")

def make_task_handler(fn):
    def handle(request_id, raw_metadata):
        try:
            data, metadata, out_info = parse_input_protocol(request_id, raw_metadata)
            written = write_output_data(request_id, to_output_bytes(fn(data, " ".join(metadata))), out_info)
            return f"DONE {written}"
        except Exception as e:
            return "ERROR " + " ".join(f"{type(e).__name__}: {e}".split())
    return handle

def parse_input_protocol(request_id, metadata, task_type=None):
    TEXT_BASED_TASKS = {"NLP_TEXTBLOB", "SENTIMENT", "REGRESSION", "STATUS"}
    force_file_output = task_type and task_type.upper() in TEXT_BASED_TASKS
    
    if len(metadata) > 0 and metadata[0] == "SHMEM":
        in_shm_name = metadata[1]
        in_size = int(metadata[2])
        out_info = None
        if len(metadata) >= 5 and "_out_" in metadata[3]:
            if not force_file_output:
                out_info = (metadata[3], int(metadata[4]))
            real_metadata = metadata[5:]
        else:
            real_metadata = metadata[3:]

        for attempt in range(15):
            try:
                shm = multiprocessing.shared_memory.SharedMemory(name=in_shm_name)
                data = bytes(shm.buf[:in_size])
                shm.close()
                return data, real_metadata, out_info
            except:
                time.sleep(0.05 + attempt * 0.01)
        raise RuntimeError("Input SHMEM read failed")

    input_path = os.path.join(WORK_DIR, f"input_{request_id}.dat")
    with open(input_path, "rb") as f:
        length_bytes = f.read(4)
        data_length = struct.unpack(">I", length_bytes)[0]
        data = f.read(data_length)
    return data, metadata, None

def write_output_data(request_id, data_bytes, out_shm_info):
    if out_shm_info:
        shm_name, capacity = out_shm_info
        if len(data_bytes) > capacity:
            raise ValueError(f"Output too large: {len(data_bytes)} bytes exceeds shared memory capacity {capacity} bytes")
        for attempt in range(15):
            try:
                shm = multiprocessing.shared_memory.SharedMemory(name=shm_name)
                shm.buf[:len(data_bytes)] = data_bytes
                shm.close()
                return len(data_bytes)
            except:
                time.sleep(0.05 + attempt * 0.01)
        raise RuntimeError("Output SHMEM write failed")
    else:
        output_path = os.path.join(WORK_DIR, f"output_{request_id}.dat")
        with open(output_path, "wb") as f:
            f.write(struct.pack(">I", len(data_bytes)))
            f.write(data_bytes)
        return len(data_bytes)

def handle_status(request_id, raw_metadata):
    try:
        status = {
            "status": "UP",
            "uptime": int(time.time() - APP_START_TIME),
            "pid": os.getpid(),
            "device": DEVICE,
            "instance": args.instance_id
        }
        _, _, out_info = parse_input_protocol(request_id, raw_metadata, "STATUS")
        written = write_output_data(request_id, json.dumps(status).encode('utf-8'), out_info)
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
        if cmd == "EXECUTE" and len(parts) >= 3:
            task_type, req_id = parts[1].upper(), parts[2]
            handler = TASK_HANDLERS.get(task_type)
            if handler: print(handler(req_id, parts[3:]), flush=True)
            elif task_type in EXTRA_TASKS: print(f"ERROR Task {task_type} requires extras(\"{EXTRA_TASKS[task_type]}\")", flush=True)
            else: print(f"ERROR Unknown task: {task_type}", flush=True)

if __name__ == "__main__":
    initialize_models()
    daemon_loop()
