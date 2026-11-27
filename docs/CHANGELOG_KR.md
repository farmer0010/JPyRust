# 변경 이력

* **Unreleased**
    * **기능:** `python(Path)`로 `uv`나 python.org 다운로드 대신 이미 있는 인터프리터에서 venv를 만듭니다(`python -m venv --system-site-packages`). 모든 OS에서 동작하며 오프라인·사내망·컨테이너 환경용입니다.
    * **기능:** `JPyRustBridge`가 `AutoCloseable`을 구현해 try-with-resources로 쓸 수 있습니다.
    * **동작 변경:** 로그를 `System.out`/`System.err` 대신 `System.Logger`(`com.jpyrust`)로 남깁니다. 네이티브 로딩과 pip 출력은 `DEBUG` 레벨이라 기본 설정에서는 보이지 않습니다.
    * **동작 변경:** 내장 작업을 선택형 extras로 옮겼습니다. `vision`(YOLO, 엣지 검출), `audio`(Whisper), `ml`(TextBlob 감정 분석, 회귀)입니다. 기본 설치는 이제 `numpy`뿐이라 `extras("vision")` / `extras("audio")` / `extras("ml")`을 부르지 않으면 `ultralytics`(AGPL-3.0), `torch`, Whisper를 설치하지 않습니다. extra 없이 내장 작업을 부르면 `Task YOLO requires extras("vision")` 오류가 납니다.
    * **기능:** 내 Python 코드 호출. 새 `jpyrust` 모듈의 `@task`로 Python 함수를 등록하고, `moduleDir(Path)`로 모듈 위치를, `requirements(Path)`로 의존성 목록을 지정한 뒤 `call(task, bytes, args)` / `call(task, text)`로 호출합니다. 결과는 바이트, UTF-8 문자열, JSON으로 돌아옵니다.
    * **동작 변경:** `.installed` 마커에 `requirements.txt`의 SHA-256을 기록하고, 다르면 `pip install`을 다시 실행합니다. 기존 설치는 다음 `initialize()`에서 `pip install`을 한 번 더 실행합니다(바뀐 게 없으면 금방 끝남).
    * **수정:** `numpy`/`opencv`가 없어도 Python 워커가 뜹니다. 이를 쓰는 내장 작업만 실패합니다.
    * **동작 변경:** Windows용 임베디드 Python(~400MB)을 더 이상 JAR에 넣지 않습니다. 첫 `initialize()` 때 python.org의 임베디드 Python 3.11.9와 버전이 고정된 `pip` wheel을 내려받고(둘 다 SHA-256 검증), PyPI에서 `requirements.txt`를 설치하므로 첫 실행에는 네트워크가 필요합니다. 이제 Windows에서도 Whisper를 쓸 수 있습니다. JAR은 1MB 미만으로 줄었고, JitPack JAR과 GitHub Release JAR이 Windows에서 똑같이 동작합니다(이전에는 JitPack JAR에 Python 번들이 아예 없었음).
    * **수정:** Windows 의존성 설치 실패를 더 이상 조용히 무시하지 않습니다 — pip이 실패하면 `initialize()`가 예외를 던집니다.
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
