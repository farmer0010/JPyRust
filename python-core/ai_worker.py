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
import wave
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
    import whisper
    WHISPER_AVAILABLE = True
except ImportError:
    WHISPER_AVAILABLE = False

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
args, unknown = parser.parse_known_args()

WORK_DIR = os.path.expanduser(f"~/.jpyrust/{args.instance_id}")
if not os.path.exists(WORK_DIR):
    os.makedirs(WORK_DIR)

TARGET_WIDTH = 640
yolo_model = None
whisper_model = None

def initialize_models():
    global yolo_model, whisper_model
    if YOLO_AVAILABLE:
        try:
            yolo_model = YOLO(args.model)
            yolo_model.to(DEVICE)
        except:
            yolo_model = None
    if WHISPER_AVAILABLE and args.whisper_model:
        try:
            whisper_model = whisper.load_model(args.whisper_model, device=DEVICE)
        except Exception:
            # openai-whisper(이 버전 기준)는 MPS 백엔드의 일부 sparse 연산을 지원하지 않아
            # Apple Silicon에서 device='mps'로 로드가 실패할 수 있다 - CPU로 재시도한다.
            try:
                whisper_model = whisper.load_model(args.whisper_model, device='cpu')
            except Exception:
                whisper_model = None
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

def handle_whisper_task(request_id, raw_metadata):
    if not WHISPER_AVAILABLE or whisper_model is None:
        return "ERROR Whisper model not available"
    try:
        raw_data, metadata, out_info = parse_input_protocol(request_id, raw_metadata)

        # 업로드 오디오는 WAV(16kHz/mono) 컨테이너로 표준화돼 들어온다고 가정한다(요구사항명세서 기준).
        # 별도 리샘플링 의존성(soundfile/ffmpeg)을 추가하지 않기 위해 표준 라이브러리 wave 모듈로
        # 직접 파싱한다 — 표준을 벗어난 입력에 대한 리샘플링은 지금 스코프가 아니다(YAGNI).
        with wave.open(io.BytesIO(raw_data), 'rb') as wf:
            n_channels = wf.getnchannels()
            frame_rate = wf.getframerate()
            frames = wf.readframes(wf.getnframes())

        audio = np.frombuffer(frames, dtype=np.int16)
        if n_channels > 1:
            audio = audio.reshape(-1, n_channels).mean(axis=1)
        audio_f32 = (audio.astype(np.float32) / 32768.0)

        result = whisper_model.transcribe(audio_f32, fp16=(DEVICE == 'cuda'))
        text = result.get("text", "").strip()
        segments = result.get("segments", [])

        if segments:
            # openai-whisper는 세그먼트별 avg_logprob(평균 로그확률, 토큰당)만 제공하고
            # 별도의 0~1 confidence 스코어는 주지 않는다. avg_logprob은 log(p)이므로
            # exp(avg_logprob)는 "토큰당 평균 확률"에 대한 근사치가 되고, 값 자체가
            # (음의 무한대, 0] 범위라 exp() 결과는 자연스럽게 [0, 1]에 가깝게 떨어진다.
            # 완벽한 확률적 의미의 confidence는 아니지만, 널리 쓰이는 근사 지표다.
            avg_logprob = sum(s.get("avg_logprob", 0.0) for s in segments) / len(segments)
            confidence = float(np.clip(np.exp(avg_logprob), 0.0, 1.0))
        else:
            confidence = 0.0

        res_json = json.dumps({"recognized_text": text, "confidence": confidence})
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
    "WHISPER": handle_whisper_task,
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
