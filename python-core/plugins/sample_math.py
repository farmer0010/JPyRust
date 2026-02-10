TASK_TYPE = "MATH_ADD"

def handle(request_id, metadata):
    try:
        if len(metadata) < 2:
            return "ERROR Missing arguments"
        
        a = float(metadata[0])
        b = float(metadata[1])
        result = a + b
        
        return f"DONE Result: {result}"
    except Exception as e:
        return f"ERROR {e}"
