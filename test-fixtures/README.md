# Regression Test Fixtures

Fixed inputs for manually re-checking YOLO/Whisper output after a `requirements.txt` or Python
version change, without guessing whether a result "looks about right." Produced during the
Python 3.13 wheel-mismatch investigation (`requirements.txt` pinned `numpy`/`torch`/etc. had no
wheels for the system Python on some machines) — see `baseline/` for the recorded expected output.

- **`bus.jpg`** — Ultralytics' own demo image (`ultralytics/assets/bus.jpg`, shipped inside the
  `ultralytics` PyPI package itself; redistribution as a test fixture is expected usage). Used for
  YOLO detection regression checks — multiple classes (person/bus/stop sign) in one image.
  `sample.png` (a 100x100 placeholder used earlier) was dropped in favor of this — it produced zero
  detections, so it couldn't actually catch a regression.
- **`baseline.wav`** — 16kHz mono WAV of "the quick brown fox jumps over the lazy dog", for Whisper
  transcription regression checks. Regenerate with:
  ```bash
  say -v Samantha -o /tmp/baseline_raw.aiff "the quick brown fox jumps over the lazy dog"
  afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/baseline_raw.aiff test-fixtures/baseline.wav
  ```
  Must use `-v Samantha` (or another standard voice) explicitly — this machine's default `say`
  voice turned out to be a novelty voice that Whisper couldn't transcribe reliably.

## `baseline/`

`baseline/yolo_baseline.txt` and `baseline/whisper_baseline.json` were captured with the
**original pinned `requirements.txt`** (`numpy==1.26.4`, `torch==2.2.0`, `openai-whisper==20231117`,
etc.) running under **Python 3.11** (via the `uv`-provisioned venv). To re-verify after a dependency
change:

```bash
yolo predict model=yolov8n.pt source=test-fixtures/bus.jpg save_txt=True save_conf=True conf=0.01
# compare against baseline/yolo_baseline.txt — the 6 boxes there should match (confidence within ~0.02)
```
Whisper: run `handle_whisper_task`'s logic (see `python-core/ai_worker.py`) against
`test-fixtures/baseline.wav` with no `language` argument (auto-detect, matches production) and
compare the transcribed text/confidence against `baseline/whisper_baseline.json`.
