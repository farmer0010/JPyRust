import sys

def process_data(input_str: str, number: int) -> str:
    """
    테스트용 함수: 문자열과 숫자를 받아서 가공 후 반환
    """
    version = sys.version.split()[0]
    result = f"[Python {version}] Received: '{input_str}' with params {number}. Processed by AI Worker."
    return result

def process_raw_data(data: bytes) -> str:
    """
    Zero-Copy(Binary) 데이터 처리 함수
    """
    try:
        import numpy as np
        # Copy-less wrapping if data is memoryview/bytes
        arr = np.frombuffer(data, dtype=np.uint8)
        info = f"NumPy Array shape={arr.shape}, mean={arr.mean():.2f}"
    except ImportError:
        # Fallback if numpy is not installed
        info = f"Raw Bytes len={len(data)} (NumPy not installed)"
    
    return f"[Python Zero-Copy] {info}"

def process_image_raw(data: memoryview, width: int, height: int, channels: int) -> str:
    """
    Zero-Copy 이미지 처리 (Grayscale)
    """
    try:
        import numpy as np
        # 1. Zero-Copy Wrapper (Writable)
        # memoryview를 그대로 사용하거나, np.frombuffer(data)를 쓰면 data가 writable할 때만 writable array가 됨.
        # Rust에서 PyBUF_WRITE로 생성했으므로 writable함.
        img = np.frombuffer(data, dtype=np.uint8).reshape((height, width, channels))
        
        # 2. Image Processing (In-Place)
        # 주의: img[...] = processed_result 형태로 원본 메모리에 덮어써야 함.
        try:
            import cv2
            # Grayscale 변환
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
            # 다시 3채널로 변환하여 원본 덮어쓰기 (데모 시각화를 위해)
            result = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
            img[:] = result # <--- 핵심: Write-Back
            method = "OpenCV"
        except ImportError:
            # Fallback: Invert Colors (Manual NumPy)
            img[:] = 255 - img
            method = "NumPy Invert"
            
        return f"[Python {method}] Processed {width}x{height} image in-place."
    except Exception as e:
        return f"Error: {e}"
