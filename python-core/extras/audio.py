import io
import json
import wave

import numpy as np
import whisper

from jpyrust import config, task

model = None
if config["whisper_model"]:
    try:
        model = whisper.load_model(config["whisper_model"], device=config["device"])
    except Exception:
        try:
            model = whisper.load_model(config["whisper_model"], device="cpu")
        except Exception:
            model = None


@task("WHISPER")
def transcribe(data, args):
    if model is None:
        raise RuntimeError("Whisper model not available")
    with wave.open(io.BytesIO(data), "rb") as wf:
        n_channels = wf.getnchannels()
        frames = wf.readframes(wf.getnframes())

    audio = np.frombuffer(frames, dtype=np.int16)
    if n_channels > 1:
        audio = audio.reshape(-1, n_channels).mean(axis=1)

    result = model.transcribe(audio.astype(np.float32) / 32768.0, fp16=(config["device"] == "cuda"))
    segments = result.get("segments", [])
    confidence = 0.0
    if segments:
        avg_logprob = sum(s.get("avg_logprob", 0.0) for s in segments) / len(segments)
        confidence = float(np.clip(np.exp(avg_logprob), 0.0, 1.0))
    return json.dumps({"recognized_text": result.get("text", "").strip(), "confidence": confidence})
