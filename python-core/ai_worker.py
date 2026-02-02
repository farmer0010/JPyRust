import sys
import os
import struct
import time
import multiprocessing.shared_memory
import argparse

import numpy as np
import cv2
try:
    import pandas as pd
    from sklearn.linear_model import LinearRegression
    from textblob import TextBlob
    ML_AVAILABLE = True
except ImportError:
    ML_AVAILABLE = False
    print("[Warning] ML libraries (pandas, sklearn, textblob) not found.", flush=True)


print("[Python Daemon] Universal Bridge Starting...", flush=True)

try:
    from ultralytics import YOLO
    YOLO_AVAILABLE = True
except ImportError as e:
    print(f"[Warning] Failed to import ultralytics: {e}", flush=True)
    YOLO_AVAILABLE = False

try:
    import torch
    DEVICE = 'cuda' if torch.cuda.is_available() else 'cpu'
    print(f"[Daemon] Device selected: {DEVICE.upper()}", flush=True)
except ImportError:
    DEVICE = 'cpu'
    print("[Daemon] Torch not found, defaulting to CPU", flush=True)

parser = argparse.ArgumentParser()
parser.add_argument("--daemon", action="store_true")
parser.add_argument("--model", type=str, default="yolov8n.pt", help="Path to YOLO model")
parser.add_argument("--conf", type=float, default=0.5, help="Confidence threshold")
args, unknown = parser.parse_known_args()

WORK_DIR = "C:/jpyrust_temp"
HEADER_SIZE = 4
TARGET_WIDTH = 640
JPEG_QUALITY = 85

yolo_model = None

def initialize_models():
    global yolo_model
    
    if YOLO_AVAILABLE:
        print(f"[Daemon] Loading YOLOv8 model: {args.model}", flush=True)
        start_time = time.time()
        try:
            yolo_model = YOLO(args.model)
            yolo_model.to(DEVICE)
            print(f"[Daemon] YOLO model loaded on {DEVICE.upper()} in {time.time() - start_time:.2f}s", flush=True)
        except Exception as e:
             print(f"[Daemon] Failed to load YOLO model: {e}", flush=True)
             yolo_model = None
    else:
        print("[Daemon] YOLO not available", flush=True)
    
    print("[Daemon] Sentiment analyzer ready (rule-based)", flush=True)

def parse_input_protocol(request_id, metadata):
    if len(metadata) > 0 and metadata[0] == "SHMEM":
        in_shm_name = metadata[1]
        in_size = int(metadata[2])
        
        if len(metadata) >= 5 and "jpyrust_out_" in metadata[3]:
            out_shm_name = metadata[3]
            out_cap = int(metadata[4])
            real_metadata = metadata[5:]
            out_info = (out_shm_name, out_cap)
        else:
            real_metadata = metadata[3:]
            out_info = None

        try:
            shm = multiprocessing.shared_memory.SharedMemory(name=in_shm_name)
            data = bytes(shm.buf[:in_size])
            shm.close()
            return data, real_metadata, out_info
        except Exception as e:
            raise RuntimeError(f"Input SHMEM read failed: {e}")

    input_path = f"{WORK_DIR}/input_{request_id}.dat"
    try:
        with open(input_path, "rb") as f:
            length_bytes = f.read(HEADER_SIZE)
            if not length_bytes:
                raise ValueError("Empty input file")
            data_length = struct.unpack(">I", length_bytes)[0]
            data = f.read(data_length)
        return data, metadata, None
    except FileNotFoundError:
        raise RuntimeError(f"Input file not found: {input_path}")

def write_output_data(request_id, data_bytes, out_shm_info):
    if out_shm_info:
        shm_name, capacity = out_shm_info
        if len(data_bytes) > capacity:
            print(f"[Error] Output size ({len(data_bytes)}) exceeds buffer capacity ({capacity})", flush=True)
            return 0 
        
        try:
            shm = multiprocessing.shared_memory.SharedMemory(name=shm_name)
            shm.buf[:len(data_bytes)] = data_bytes
            shm.close()
            return len(data_bytes)
        except Exception as e:
            print(f"[Error] Output SHMEM write failed: {e}", flush=True)
            return 0
            
    else:
        output_path = f"{WORK_DIR}/output_{request_id}.dat"
        with open(output_path, "wb") as f:
            f.write(struct.pack(">I", len(data_bytes)))
            f.write(data_bytes)
        return len(data_bytes)

def resize_image(image: np.ndarray, target_width: int) -> np.ndarray:
    height, width = image.shape[:2]
    if width <= target_width:
        return image
    scale = target_width / width
    new_height = int(height * scale)
    return cv2.resize(image, (target_width, new_height), interpolation=cv2.INTER_LINEAR)

def handle_yolo_task(request_id: str, raw_metadata: list) -> str:
    try:
        raw_data, metadata, out_info = parse_input_protocol(request_id, raw_metadata)
        
        if len(metadata) < 3:
            return "ERROR Missing metadata (width, height, channels)"
        
        width = int(metadata[0])
        height = int(metadata[1])
        channels = int(metadata[2])
        
        start_time = time.time()
        
        image = np.frombuffer(raw_data, dtype=np.uint8).reshape((height, width, channels))
        original_shape = image.shape
        
        image = resize_image(image, TARGET_WIDTH)
        
        if yolo_model is not None:
            results = yolo_model(image, conf=args.conf, verbose=False)
            detections = len(results[0].boxes) if results[0].boxes is not None else 0
            annotated_frame = results[0].plot()
        else:
            annotated_frame = image
            detections = 0
        
        encode_params = [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY]
        success, jpeg_data = cv2.imencode('.jpg', annotated_frame, encode_params)
        if not success:
            return "ERROR JPEG encoding failed"
        
        data_to_write = jpeg_data.tobytes()
        
        bytes_written = write_output_data(request_id, data_to_write, out_info)
        
        elapsed = (time.time() - start_time) * 1000
        mode = "FULL-SHMEM" if out_info else ("SHMEM-IN" if raw_metadata[0] == "SHMEM" else "FILE")
        
        print(f"[YOLO] ID:{request_id[:8]} | {mode} | {detections} objs | {elapsed:.0f}ms", flush=True)
        
        return f"DONE {bytes_written}"
        
    except Exception as e:
        print(f"[YOLO Error] {e}", flush=True)
        import traceback
        traceback.print_exc()
        return f"ERROR {e}"

def handle_sentiment_task(request_id: str, raw_metadata: list) -> str:
    try:
        raw_data, metadata, out_info = parse_input_protocol(request_id, raw_metadata)
        
        start_time = time.time()
        text = raw_data.decode('utf-8')
        
        text_lower = text.lower()
        negative_words = ['bad', 'sad', 'terrible', 'awful', 'hate', 'angry', 'fail']
        positive_words = ['good', 'great', 'happy', 'love', 'amazing', 'best']
        
        neg_count = sum(1 for word in negative_words if word in text_lower)
        pos_count = sum(1 for word in positive_words if word in text_lower)
        
        if pos_count > neg_count:
            sentiment = "POSITIVE"
            confidence = min(0.5 + pos_count * 0.1, 0.99)
        elif neg_count > pos_count:
            sentiment = "NEGATIVE"
            confidence = min(0.5 + neg_count * 0.1, 0.99)
        else:
            sentiment = "NEUTRAL"
            confidence = 0.5
        
        result = f"{sentiment} (confidence: {confidence:.2f})"
        result_bytes = result.encode('utf-8')
        
        bytes_written = write_output_data(request_id, result_bytes, out_info)
        
        elapsed = (time.time() - start_time) * 1000
        print(f"[SENTIMENT] ID:{request_id[:8]} | {len(text)} chars | {sentiment} | {elapsed:.0f}ms", flush=True)
        
        return f"DONE {bytes_written}"
        
    except Exception as e:
        print(f"[SENTIMENT Error] {e}", flush=True)
        return f"ERROR {e}"

def handle_enhanced_sentiment(request_id: str, raw_metadata: list) -> str:
    if not ML_AVAILABLE:
        return "ERROR ML libraries not installed"
        
    try:
        raw_data, metadata, out_info = parse_input_protocol(request_id, raw_metadata)
        text = raw_data.decode('utf-8')
        
        blob = TextBlob(text)
        sentiment_score = blob.sentiment.polarity # -1.0 to 1.0
        subjectivity = blob.sentiment.subjectivity
        
        if sentiment_score > 0.1:
            sentiment = "POSITIVE"
        elif sentiment_score < -0.1:
            sentiment = "NEGATIVE"
        else:
            sentiment = "NEUTRAL"
            
        result = f"{sentiment} (Polarity: {sentiment_score:.2f}, Subjectivity: {subjectivity:.2f})"
        result_bytes = result.encode('utf-8')
        
        bytes_written = write_output_data(request_id, result_bytes, out_info)
        return f"DONE {bytes_written}"
    except Exception as e:
        return f"ERROR {e}"

def handle_regression_task(request_id: str, raw_metadata: list) -> str:
    if not ML_AVAILABLE:
        return "ERROR ML libraries not installed"

    try:
        raw_data, metadata, out_info = parse_input_protocol(request_id, raw_metadata)
        # Input: JSON string "[[1, 2], [2, 4], [3, 6]]"
        import json
        json_str = raw_data.decode('utf-8')
        data = json.loads(json_str)
        
        df = pd.DataFrame(data, columns=['x', 'y'])
        X = df[['x']]
        y = df['y']
        
        model = LinearRegression()
        model.fit(X, y)
        
        slope = model.coef_[0]
        intercept = model.intercept_
        
        result = f"Slope: {slope:.4f}, Intercept: {intercept:.4f}"
        result_bytes = result.encode('utf-8')
        
        bytes_written = write_output_data(request_id, result_bytes, out_info)
        return f"DONE {bytes_written}"
    except Exception as e:
        return f"ERROR {e}"

def handle_edge_detection(request_id: str, raw_metadata: list) -> str:
    try:
        raw_data, metadata, out_info = parse_input_protocol(request_id, raw_metadata)
        
        if len(metadata) < 3:
            return "ERROR Missing metadata"
            
        width = int(metadata[0])
        height = int(metadata[1])
        channels = int(metadata[2])
        
        image = np.frombuffer(raw_data, dtype=np.uint8).reshape((height, width, channels))
        
        # Canny Edge Detection
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        edges = cv2.Canny(gray, 100, 200)
        edges_bgr = cv2.cvtColor(edges, cv2.COLOR_GRAY2BGR)
        
        success, jpeg_data = cv2.imencode('.jpg', edges_bgr)
        if not success:
            return "ERROR Encode failed"
            
        bytes_written = write_output_data(request_id, jpeg_data.tobytes(), out_info)
        return f"DONE {bytes_written}"
    except Exception as e:
        return f"ERROR {e}"


TASK_HANDLERS = {
    "YOLO": handle_yolo_task,
    "SENTIMENT": handle_sentiment_task, # Legacy rule-based
    "NLP_TEXTBLOB": handle_enhanced_sentiment,
    "REGRESSION": handle_regression_task,
    "EDGE_DETECT": handle_edge_detection,
}


def daemon_loop():
    print("[Daemon] Entering command loop...", flush=True)
    print("READY", flush=True)
    
    try:
        while True:
            try:
                line = sys.stdin.readline()
                if not line: break
                
                command = line.strip()
                if not command: continue
                
                parts = command.split()
                cmd = parts[0].upper()
                
                if cmd == "EXIT":
                    print("EXITING", flush=True)
                    break
                
                elif cmd == "EXECUTE":
                    if len(parts) < 3:
                        print("ERROR Missing arguments", flush=True)
                        continue
                    
                    task_type = parts[1].upper()
                    request_id = parts[2]
                    metadata = parts[3:] if len(parts) > 3 else []
                    
                    handler = TASK_HANDLERS.get(task_type)
                    if handler:
                        result = handler(request_id, metadata)
                        print(result, flush=True)
                    else:
                        print(f"ERROR Unknown task type: {task_type}", flush=True)

                elif cmd == "PROCESS":
                     if len(parts) >= 5:
                        width, height, channels, request_id = parts[1], parts[2], parts[3], parts[4]
                        result = handle_yolo_task(request_id, [width, height, channels])
                        print(result, flush=True)
                
            except (KeyboardInterrupt, EOFError):
                break
            except Exception as e:
                 print(f"ERROR Unexpected: {e}", flush=True)
    except:
        pass

if __name__ == "__main__":
    initialize_models()
    daemon_loop()
