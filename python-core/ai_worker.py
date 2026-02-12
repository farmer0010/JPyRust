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
import numpy as np
import cv2

if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

APP_START_TIME = time.time()

try:
    import pandas as pd
    from sklearn.linear_model import LinearRegression
    from textblob import TextBlob
    ML_AVAILABLE = True
except ImportError:
    ML_AVAILABLE = False

try:
    from ultralytics import YOLO
    YOLO_AVAILABLE = True
except ImportError:
    YOLO_AVAILABLE = False

try:
    import torch
    DEVICE = 'cuda' if torch.cuda.is_available() else 'cpu'
except ImportError:
    DEVICE = 'cpu'

parser = argparse.ArgumentParser()
parser.add_argument("--daemon", action="store_true")
parser.add_argument("--model", type=str, default="yolov8n.pt")
parser.add_argument("--conf", type=float, default=0.5)
parser.add_argument("--mem-key", type=str, default="")
parser.add_argument("--instance-id", type=str, default="default")
args, unknown = parser.parse_known_args()

WORK_DIR = os.path.expanduser(f"~/.jpyrust/{args.instance_id}")
if not os.path.exists(WORK_DIR):
    os.makedirs(WORK_DIR)

TARGET_WIDTH = 640
yolo_model = None

def initialize_models():
    global yolo_model
    if YOLO_AVAILABLE:
        try:
            yolo_model = YOLO(args.model)
            yolo_model.to(DEVICE)
        except:
            yolo_model = None
    load_plugins()

def load_plugins():
    plugin_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "plugins")
    if not os.path.exists(plugin_dir):
        return
    plugin_files = glob.glob(os.path.join(plugin_dir, "*.py"))
    for plugin_file in plugin_files:
        if "__init__" in plugin_file: continue
        try:
            module_name = os.path.splitext(os.path.basename(plugin_file))[0]
            spec = importlib.util.spec_from_file_location(module_name, plugin_file)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            if hasattr(module, "TASK_TYPE") and hasattr(module, "handle"):
                TASK_HANDLERS[module.TASK_TYPE] = module.handle
        except:
            pass

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
        if len(data_bytes) > capacity: return 0
        for attempt in range(15):
            try:
                shm = multiprocessing.shared_memory.SharedMemory(name=shm_name)
                shm.buf[:len(data_bytes)] = data_bytes
                shm.close()
                return len(data_bytes)
            except:
                time.sleep(0.05 + attempt * 0.01)
        return 0
    else:
        output_path = os.path.join(WORK_DIR, f"output_{request_id}.dat")
        with open(output_path, "wb") as f:
            f.write(struct.pack(">I", len(data_bytes)))
            f.write(data_bytes)
        return len(data_bytes)

def resize_image(image, target_width):
    h, w = image.shape[:2]
    if w <= target_width: return image
    scale = target_width / w
    return cv2.resize(image, (target_width, int(h * scale)), interpolation=cv2.INTER_LINEAR)

def handle_yolo_task(request_id, raw_metadata):
    try:
        raw_data, metadata, out_info = parse_input_protocol(request_id, raw_metadata)
        nparr = np.frombuffer(raw_data, dtype=np.uint8)
        image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        if image is None: return "ERROR Failed to decode image"
        image = resize_image(image, TARGET_WIDTH)
        detections = []
        if yolo_model:
            results = yolo_model(image, conf=args.conf, verbose=False)
            boxes = results[0].boxes
            if boxes:
                for box in boxes:
                    x1, y1, x2, y2 = box.xyxy[0].tolist()
                    detections.append({
                        "bbox": [x1, y1, x2-x1, y2-y1],
                        "label": results[0].names[int(box.cls[0])],
                        "score": float(box.conf[0])
                    })
        res_json = json.dumps({"detections": detections})
        written = write_output_data(request_id, res_json.encode('utf-8'), out_info)
        return f"DONE {written}"
    except Exception as e:
        return f"ERROR {e}"

def handle_nlp_task(request_id, raw_metadata):
    if not ML_AVAILABLE: return "ERROR ML not installed"
    try:
        raw_data, _, out_info = parse_input_protocol(request_id, raw_metadata, "NLP_TEXTBLOB")
        text = raw_data.decode('utf-8')
        blob = TextBlob(text)
        pol = blob.sentiment.polarity
        sent = "POSITIVE" if pol > 0.1 else ("NEGATIVE" if pol < -0.1 else "NEUTRAL")
        res = f"{sent} (Polarity: {pol:.2f})"
        written = write_output_data(request_id, res.encode('utf-8'), out_info)
        return f"DONE {written}"
    except Exception as e:
        return f"ERROR {e}"

def handle_regression_task(request_id, raw_metadata):
    if not ML_AVAILABLE: return "ERROR ML not installed"
    try:
        raw_data, _, out_info = parse_input_protocol(request_id, raw_metadata, "REGRESSION")
        data = json.loads(raw_data.decode('utf-8'))
        df = pd.DataFrame(data, columns=['x', 'y'])
        model = LinearRegression().fit(df[['x']], df['y'])
        res = f"Slope: {model.coef_[0]:.4f}, Intercept: {model.intercept_:.4f}"
        written = write_output_data(request_id, res.encode('utf-8'), out_info)
        return f"DONE {written}"
    except Exception as e:
        return f"ERROR {e}"

def handle_edge_task(request_id, raw_metadata):
    try:
        raw_data, meta, out_info = parse_input_protocol(request_id, raw_metadata)
        w, h, c = int(meta[0]), int(meta[1]), int(meta[2])
        img = np.frombuffer(raw_data, dtype=np.uint8).reshape((h, w, c))
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        edges = cv2.cvtColor(cv2.Canny(gray, 100, 200), cv2.COLOR_GRAY2BGR)
        _, jpg = cv2.imencode('.jpg', edges)
        written = write_output_data(request_id, jpg.tobytes(), out_info)
        return f"DONE {written}"
    except Exception as e:
        return f"ERROR {e}"

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
    "YOLO": handle_yolo_task,
    "NLP_TEXTBLOB": handle_nlp_task,
    "REGRESSION": handle_regression_task,
    "EDGE_DETECT": handle_edge_task,
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
            else: print(f"ERROR Unknown task: {task_type}", flush=True)

if __name__ == "__main__":
    initialize_models()
    daemon_loop()
