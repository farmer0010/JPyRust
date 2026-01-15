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
