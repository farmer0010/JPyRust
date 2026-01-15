import sys

def process_data(input_str: str, number: int) -> str:
    """
    테스트용 함수: 문자열과 숫자를 받아서 가공 후 반환
    """
    version = sys.version.split()[0]
    result = f"[Python {version}] Received: '{input_str}' with params {number}. Processed by AI Worker."
    return result
