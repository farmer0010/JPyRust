TASK_TYPE = "MATH_ADD"

def handle(request_id, metadata):
    # metadata: [a, b]
    try:
        if len(metadata) < 2:
            return "ERROR Missing arguments"
        
        a = float(metadata[0])
        b = float(metadata[1])
        result = a + b
        
        # Simple string return for this plugin
        return f"DONE Result: {result}"
    except Exception as e:
        return f"ERROR {e}"
