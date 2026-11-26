import json

import cv2
import numpy as np
from ultralytics import YOLO

from jpyrust import config, task

TARGET_WIDTH = 640

try:
    model = YOLO(config["model"])
    model.to(config["device"])
except Exception:
    model = None


def decode_image(data):
    image = cv2.imdecode(np.frombuffer(data, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("Failed to decode image")
    h, w = image.shape[:2]
    if w <= TARGET_WIDTH:
        return image
    return cv2.resize(image, (TARGET_WIDTH, int(h * TARGET_WIDTH / w)), interpolation=cv2.INTER_LINEAR)


@task("YOLO")
def yolo(data, args):
    image = decode_image(data)
    detections = []
    if model:
        results = model(image, conf=config["conf"], verbose=False)
        for box in results[0].boxes or []:
            x1, y1, x2, y2 = box.xyxy[0].tolist()
            detections.append({
                "bbox": [x1, y1, x2 - x1, y2 - y1],
                "label": results[0].names[int(box.cls[0])],
                "score": float(box.conf[0]),
            })
    return json.dumps({"detections": detections})


@task("EDGE_DETECT")
def edge_detect(data, args):
    w, h, c = (int(v) for v in args.split()[:3])
    img = np.frombuffer(data, dtype=np.uint8).reshape((h, w, c))
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    edges = cv2.cvtColor(cv2.Canny(gray, 100, 200), cv2.COLOR_GRAY2BGR)
    _, jpg = cv2.imencode(".jpg", edges)
    return jpg.tobytes()
