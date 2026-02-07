```python
import sys
import multiprocessing
import time

def process_data(input_str: str, number: int) -> int:
    """
    테스트용 함수: 문자열과 숫자를 받아서 가공 후 반환
    가공된 데이터를 SharedMemory에 쓰고, 쓰여진 바이트 수를 반환합니다.
    """
    version = sys.version.split()[0]
    result_str = f"[Python {version}] Received: '{input_str}' with params {number}. Processed by AI Worker."

    # Assume shm_name is passed or determined elsewhere, and data_bytes is the result_str encoded
    # For this example, let's assume shm_name is 'my_shared_memory' and data_bytes is result_str.encode()
    # In a real scenario, shm_name would need to be created/managed.
    shm_name = 'my_shared_memory_output' # Placeholder, needs to be managed externally
    data_bytes = result_str.encode('utf-8')

    try:
        for attempt in range(5):
            try:
                # Attempt to connect to the shared memory and write data
                # This assumes the shared memory segment 'shm_name' has been created
                # with sufficient size by another process.
                shm = multiprocessing.shared_memory.SharedMemory(name=shm_name)
                shm.buf[:len(data_bytes)] = data_bytes
                shm.close()
                break # Success, exit retry loop
            except FileNotFoundError:
                # If the shared memory segment doesn't exist yet, or other connection issue
                # For PermissionError, it might be that another process holds a lock or is still initializing.
                # We'll treat FileNotFoundError as a transient issue for retries here,
                # though in a real system, it might indicate a setup problem.
                if attempt == 4:
                    raise # Re-raise if last attempt
                time.sleep(0.01) # Wait a bit before retrying
            except PermissionError:
                # This might happen if another process is still holding a lock or the segment is being created/destroyed.
                if attempt == 4:
                    raise # Re-raise if last attempt
                time.sleep(0.01) # Wait a bit before retrying
        return len(data_bytes)
    except Exception as e:
        print(f"[Error] Output SHMEM write failed: {e}", flush=True)
        return 0
```
