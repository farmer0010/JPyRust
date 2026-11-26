# 변경 이력

* **Unreleased**
    * **동작 변경:** `processImage`, `processAudio`, 새로 추가된 `sendTask`는 실패 시 `null` 대신 `RuntimeException`을 던집니다. `initialize()` 전이나 `close()` 후에 호출하면 `IllegalStateException`이 발생합니다. `initialize()`는 Python 데몬 기동에 실패하면 예외를 던집니다(이전에는 로그만 남기고 반쯤 초기화된 상태로 남았음). [README](README_KR.md)의 *오류 처리와 타임아웃*을 참고하세요.
    * **수정:** Rust JNI 진입점을 `catch_unwind`로 감싸, 네이티브 panic이나 JNI 오류가 JVM을 종료시키지 않고 Java 예외로 전달됩니다. `executeTask`와 `close()`를 동기화해, 호출 도중 `close()`가 네이티브 상태를 해제하는 문제를 막았습니다.
    * **수정:** Python 데몬 대기에 실제로 동작하는 타임아웃을 적용했습니다(기동 600초, 작업 300초). 멈추거나 죽은 데몬은 종료되고 다음 호출 때 다시 뜹니다. 기존 600초 기동 타임아웃은 blocking 읽기 때문에 한 번도 동작하지 않았습니다.
    * **수정:** 결과가 1MB 공유 메모리 출력 버퍼를 넘으면 빈 배열 대신 오류를 반환합니다.
    * **기능:** `sendTask(taskType, metadata)`로 `ai_worker.py`에 등록된 임의 작업을 호출하고 결과를 UTF-8 문자열로 받습니다.
    * **보안:** Windows 공유 메모리 매핑 권한을 Everyone에서 소유 사용자와 SYSTEM(`D:P(A;;GA;;;OW)(A;;GA;;;SY)`)으로 제한했습니다.
    * **버그 수정:** macOS/Linux venv 프로비저닝을 `PATH`에서 시스템 `python3.12`/`python3.11`/`python3.13`/`python3`를 찾는 방식에서 `uv venv --python 3.11 --seed`로 전환 — 설치돼 있는 버전에 의존하는 대신 정확한 인터프리터 버전을 고정한다. 시스템 `python3`가 3.13 이상뿐이라 `requirements.txt`에 고정된 `numpy`/`torch` 등의 wheel이 없어 설치가 실패하던 로컬 환경 문제를 해결한다.
    * **기능:** OpenAI Whisper 기반 오디오 전사 지원 — Java 쪽에 `processAudio(data, length, sampleRate)` 신규 추가, `ai_worker.py`에 `WHISPER` 태스크 핸들러 추가. 기존 범용 `executeTask` JNI 호출을 그대로 재사용(새 native 진입점 없음). 이미 가변 길이 payload를 지원하는 파일 기반 IPC 폴백 경로를 그대로 탄다.
    * **기능:** `initialize(workDir, modelPath, confidence, memoryKey, whisperModelPath)` 오버로드 추가 — 기존 YOLO 모델/신뢰도와 함께 Whisper 모델(이름 또는 로컬 체크포인트 경로)을 설정 가능.
    * **버그 수정:** `initialize(...)`에 넘긴 `modelPath`/`confidence`가 Rust에서는 받아놓고도 Python 데몬 프로세스에 실제로 전달되지 않던 문제 — Python은 무엇을 넘기든 항상 자체 argparse 기본값을 썼다. 이제 데몬 기동 시 `--model`/`--conf`/`--whisper-model`을 실제로 전달한다.
    * **참고:** `openai-whisper`는 PyPI에 wheel을 배포하지 않아(sdist만 존재) Windows 임베디드 오프라인 배포판(`downloadWheels`/`stagePython`)에서는 제외했다 — Windows 사용자는 YOLO/이미지 기능은 완전 오프라인으로 쓸 수 있지만 Whisper는 최초 설치 시 네트워크가 필요하다. macOS/Linux(venv 부트스트랩)는 `--no-build-isolation`과 구버전 `setuptools` 고정으로 설치한다(레거시 `setup.py`가 `pkg_resources`를 요구하기 때문).
    * **기능:** macOS/Linux에서 시스템 `python3` 기반 venv로 완전한 네이티브 설치 경로를 구성하도록 개선.
    * **성능:** 공유 메모리 이름 규칙을 플랫폼 간(Rust ↔ Python) 일관되게, macOS `shm_open` 제한 안에 들어오도록 튜닝.
    * **기능:** Apple Silicon에서 CUDA/CPU와 함께 MPS(Metal)를 디바이스 옵션으로 추가.
    * **정리:** 내부 스크립트 경로와 레거시 subprocess 벤치마크 프로토콜을 플랫폼 간 일관되게 정리.

* **v1.3.1**
    * **Linux/Docker 지원:** `setupEmbeddedPython`이 OS를 감지해서 Linux에서는 Windows 전용 임베디드 배포판 대신 시스템 `python3`를 사용.
    * **커스텀 파라미터:** 커스텀 AI 모델 설정을 위한 `initialize(workDir, modelPath, confidence)` 오버로드 추가.
    * **하위 호환:** 기존 `initialize(workDir)`도 기본값(`yolov8n.pt`, `0.5f`)으로 그대로 동작.

* **v1.3.0**
    * **대규모 리팩토링:** 멀티 인스턴스(Multi-Instance) 아키텍처로 전환.
    * **Breaking Change:** Static 메서드 제거 및 생성자(`new`) 기반 초기화 도입.
    * **기능 추가:** 인스턴스별 작업 디렉토리 격리 (`~/.jpyrust/cam1`).

* **v1.2.0**
    * **성능:** Win32 API 직접 호출을 통해 Windows 환경에서 공유 메모리(SHMEM) 기능 복구.
    * **보안:** 커스텀 보안 기술자(Security Descriptor)를 적용하여 `WinError 5` 근본 해결.

* **v1.1.0**
    * 초기 Windows 지원 및 파일 기반 IPC 폴백 모드 추가.
