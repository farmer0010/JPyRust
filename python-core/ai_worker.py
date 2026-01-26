#!/usr/bin/env python3
"""
ai_worker.py - Universal Bridge Daemon with Shared Memory Support

Supports multiple task types:
  - YOLO: Image object detection
  - SENTIMENT: Text sentiment analysis

Protocol:
  - EXECUTE <task_type> <request_id> <metadata...>
  - EXIT

Metadata formats:
  - File Mode: <w> <h> <c> (YOLO) | (empty) (SENTIMENT)
  - SHMEM Mode: SHMEM <shm_name> <size> <original_metadata...>
"""

import sys
import os
import struct
import time
import multiprocessing.shared_memory

# ============================================
# INITIALIZATION
# ============================================
print("[Python Daemon] Universal Bridge Starting...", flush=True)
print(f"[Debug] sys.executable: {sys.executable}", flush=True)

import numpy as np
import cv2

try:
    from ultralytics import YOLO
    YOLO_AVAILABLE = True
except ImportError as e:
    print(f"[Warning] Failed to import ultralytics: {e}", flush=True)
    YOLO_AVAILABLE = False

# Configuration
WORK_DIR = "C:/jpyrust_temp"
HEADER_SIZE = 4
TARGET_WIDTH = 640
JPEG_QUALITY = 85

# ============================================
# MODEL INITIALIZATION
# ============================================
yolo_model = None

def initialize_models():
    """Initialize all AI models at startup."""
    global yolo_model
    
    # YOLO Model
    if YOLO_AVAILABLE:
        print("[Daemon] Loading YOLOv8 model...", flush=True)
        start_time = time.time()
        yolo_model = YOLO("yolov8n.pt")
        print(f"[Daemon] YOLO model loaded in {time.time() - start_time:.2f}s", flush=True)
    else:
        print("[Daemon] YOLO not available", flush=True)
    
    # Sentiment model is rule-based, no loading needed
    print("[Daemon] Sentiment analyzer ready (rule-based)", flush=True)


# ============================================
# DATA READER HELPER
# ============================================
def read_input_data(request_id, metadata):
    """
    Reads input data either from Shared Memory or File.
    Returns (bytes_data, remaining_metadata)
    """
    # 1. Check for Shared Memory Protocol
    if len(metadata) >= 3 and metadata[0] == "SHMEM":
        shm_name = metadata[1]
        data_size = int(metadata[2])
        real_metadata = metadata[3:]
        
        try:
            shm = multiprocessing.shared_memory.SharedMemory(name=shm_name)
            # Create a copy of the data (or allow zero-copy if possible downstream)
            # Logic: Read safely, close handle
            data = bytes(shm.buf[:data_size]) 
            shm.close() # Detach
            return data, real_metadata
        except Exception as e:
            raise RuntimeError(f"Shared Memory read failed: {e}")

    # 2. Default: File Protocol
    input_path = f"{WORK_DIR}/input_{request_id}.dat"
    try:
        with open(input_path, "rb") as f:
            length_bytes = f.read(HEADER_SIZE)
            if not length_bytes:
                raise ValueError("Empty input file")
            data_length = struct.unpack(">I", length_bytes)[0]
            data = f.read(data_length)
        return data, metadata
    except FileNotFoundError:
        raise RuntimeError(f"Input file not found: {input_path}")


# ============================================
# TASK HANDLERS
# ============================================

def resize_image(image: np.ndarray, target_width: int) -> np.ndarray:
    height, width = image.shape[:2]
    if width <= target_width:
        return image
    scale = target_width / width
    new_height = int(height * scale)
    return cv2.resize(image, (target_width, new_height), interpolation=cv2.INTER_LINEAR)


def handle_yolo_task(request_id: str, raw_metadata: list) -> str:
    """
    YOLO Object Detection Task.
    """
    try:
        # Read Data (SHMEM or File)
        raw_data, metadata = read_input_data(request_id, raw_metadata)
        
        if len(metadata) < 3:
            return "ERROR Missing metadata (width, height, channels)"
        
        width = int(metadata[0])
        height = int(metadata[1])
        channels = int(metadata[2])
        
        start_time = time.time()
        
        # Create numpy array
        image = np.frombuffer(raw_data, dtype=np.uint8).reshape((height, width, channels))
        original_shape = image.shape
        
        # Resize
        image = resize_image(image, TARGET_WIDTH)
        
        # YOLO Inference
        if yolo_model is not None:
            results = yolo_model(image, verbose=False)
            detections = len(results[0].boxes) if results[0].boxes is not None else 0
            annotated_frame = results[0].plot()
        else:
            annotated_frame = image
            detections = 0
        
        # Encode as JPEG
        encode_params = [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY]
        success, jpeg_data = cv2.imencode('.jpg', annotated_frame, encode_params)
        if not success:
            return "ERROR JPEG encoding failed"
        
        data_to_write = jpeg_data.tobytes()
        
        # Write output (Still file-based for Level 1)
        output_path = f"{WORK_DIR}/output_{request_id}.dat"
        with open(output_path, "wb") as f:
            f.write(struct.pack(">I", len(data_to_write)))
            f.write(data_to_write)
        
        elapsed = (time.time() - start_time) * 1000
        # Log if SHMEM was used
        mode = "SHMEM" if raw_metadata[0] == "SHMEM" else "FILE"
        print(f"[YOLO] ID:{request_id[:8]} | {mode} | {detections} objs | {elapsed:.0f}ms", flush=True)
        
        return f"DONE {detections}"
        
    except Exception as e:
        print(f"[YOLO Error] {e}", flush=True)
        import traceback
        traceback.print_exc()
        return f"ERROR {e}"


def handle_sentiment_task(request_id: str, raw_metadata: list) -> str:
    """
    Sentiment Analysis Task.
    """
    try:
        # Read Data
        raw_data, metadata = read_input_data(request_id, raw_metadata)
        
        start_time = time.time()
        text = raw_data.decode('utf-8')
        
        # Simple rule-based sentiment analysis
        text_lower = text.lower()
        
        negative_words = ['bad', 'sad', 'terrible', 'awful', 'hate', 'angry', 'disappointed', 'horrible', 'worst', 'fail', 'poor']
        positive_words = ['good', 'great', 'excellent', 'happy', 'love', 'amazing', 'wonderful', 'best', 'awesome', 'fantastic']
        
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
        
        # Write output
        result_bytes = result.encode('utf-8')
        output_path = f"{WORK_DIR}/output_{request_id}.dat"
        with open(output_path, "wb") as f:
            f.write(struct.pack(">I", len(result_bytes)))
            f.write(result_bytes)
        
        elapsed = (time.time() - start_time) * 1000
        mode = "SHMEM" if raw_metadata[0] == "SHMEM" else "FILE"
        print(f"[SENTIMENT] ID:{request_id[:8]} | {mode} | {sentiment} | {elapsed:.0f}ms", flush=True)
        
        return f"DONE {sentiment}"
        
    except Exception as e:
        print(f"[SENTIMENT Error] {e}", flush=True)
        return f"ERROR {e}"


# ============================================
# TASK DISPATCHER
# ============================================

TASK_HANDLERS = {
    "YOLO": handle_yolo_task,
    "SENTIMENT": handle_sentiment_task,
}


def daemon_loop():
    """
    Main daemon loop with task-based dispatching.
    """
    print("[Daemon] Entering command loop...", flush=True)
    print("READY", flush=True)
    
    try:
        while True:
            try:
                line = sys.stdin.readline()
                
                if not line:
                    break
                
                command = line.strip()
                if not command:
                    continue
                
                parts = command.split()
                cmd = parts[0].upper()
                
                if cmd == "EXIT":
                    print("EXITING", flush=True)
                    break
                
                elif cmd == "EXECUTE":
                    # Format: EXECUTE <task_type> <request_id> <metadata...>
                    if len(parts) < 3:
                        print("ERROR Missing arguments", flush=True)
                        continue
                    
                    task_type = parts[1].upper()
                    request_id = parts[2]
                    metadata = parts[3:] if len(parts) > 3 else []
                    
                    # Dispatch to handler
                    handler = TASK_HANDLERS.get(task_type)
                    if handler:
                        result = handler(request_id, metadata)
                        print(result, flush=True)
                    else:
                        print(f"ERROR Unknown task type: {task_type}", flush=True)
                
                # Legacy support for old PROCESS command (Keep for safety)
                elif cmd == "PROCESS":
                    if len(parts) >= 5:
                        # Convert legacy PROCESS to YOLO task (always FILE mode)
                        width, height, channels, request_id = parts[1], parts[2], parts[3], parts[4]
                        # Construct legacy metadata for File I/O
                        result = handle_yolo_task(request_id, [width, height, channels])
                        print(result, flush=True)
                    else:
                        print("ERROR Invalid PROCESS command", flush=True)
                
                else:
                    print(f"ERROR Unknown command: {cmd}", flush=True)
                    
            except (KeyboardInterrupt, EOFError):
                break
            except OSError:
                break
            except Exception as e:
                try:
                    print(f"ERROR Unexpected: {e}", flush=True)
                except:
                    pass
    except:
        pass


if __name__ == "__main__":
    initialize_models()
    
    if len(sys.argv) == 1 or (len(sys.argv) == 2 and sys.argv[1].lower() == "--daemon"):
        daemon_loop()
    else:
        print("Usage: python ai_worker.py [--daemon]")
        print("Supported tasks: YOLO, SENTIMENT, SHMEM-IPC")
