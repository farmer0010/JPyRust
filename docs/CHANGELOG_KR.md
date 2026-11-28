# 변경 이력

* **Unreleased**
    * **기능:** 스트리밍. `yield`하는 작업을 `callStream(task, input, args)`로 `java.util.stream.Stream`처럼 읽습니다. 값은 만들어지는 대로 도착하고, 워커는 Java가 다음 값을 요청할 때까지 기다리며, 스트림을 닫으면 제너레이터가 멈춥니다(`finally` 블록 실행). 작업 타임아웃은 값 하나마다 적용됩니다. `JPyRustTemplate.callStream`은 스트림당 타이머 하나를 기록하고, `examples/spring-boot`는 제너레이터를 Server-Sent Events로 보냅니다. 제너레이터 작업을 `call`로 부르면 이제 명확한 `TaskFailedException`이 납니다.
    * **기능:** `NDArray`로 타입과 shape가 있는 배열을 Python과 주고받습니다. `bridge.call(task, NDArray.of(floats, 32, 128), args)`는 작업에 `numpy.ndarray`를 넘기고, 작업이 만든 배열을 돌려받습니다. 전송 형식이 NumPy `.npy`라 JSON이나 원소별 인코딩이 없으며, float32, float64, int8/16/32/64, uint8, bool을 지원합니다. `numpy.ndarray`를 반환하는 작업은 이제 JSON 직렬화 오류 대신 `.npy` 바이트를 돌려줍니다.
    * **기능:** `pythonVersion("3.12")`로 내려받을 CPython을 고릅니다. 3.11(기본), 3.12, 3.13, 3.14 중 하나이며 Spring starter에서는 `jpyrust.python-version`입니다. 바꾸면 인스턴스의 venv를 다시 만듭니다. 기본 의존성은 3.11~3.12에서 `numpy` 1.26.4, 3.13 이상에서 2.3.5로 고정하고, 내장 extras는 3.11~3.12에서만 설치되며 그보다 새 버전에서는 바로 실패합니다.
    * **동작 변경:** Windows도 python.org의 임베디드 3.11.9와 pip wheel 대신 python-build-standalone의 CPython 3.11.18을 내려받아 macOS·Linux와 같은 방식으로 venv를 만듭니다. 기존 Windows 인스턴스 디렉터리는 다음 `initialize()` 때 한 번 다시 설치됩니다.

* **v2.0.1**
    * **수정:** `jpyrust-spring-boot-starter`가 Spring Boot 4에서도 `jpyRust` 헬스 체크와 `jpyrust.calls` 타이머를 등록합니다. Boot 4에서 health·metrics 클래스의 패키지가 바뀌어, 4.x에서는 호출은 되지만 둘 다 조용히 빠져 있었습니다. 이제 starter를 Boot 3.2.1과 4.2.0에서 모두 테스트합니다.
    * **기타:** `windows-sys` 0.61, GitHub Actions 버전 업데이트, 쓰지 않는 `lazy_static` 의존성 제거. 예제와 `demo-web`은 Spring Boot 4.2를 씁니다.

* **v2.0.0**
    * **1.x에서 올릴 때** — 먼저 읽어 주세요:
        * **내장 작업은 선택형 extras입니다.** `vision`(YOLO, 엣지 검출), `audio`(Whisper), `ml`(TextBlob 감정 분석, 회귀). 기본 설치는 `numpy`뿐이라 `extras("vision")` / `extras("audio")` / `extras("ml")`을 부르지 않으면 `ultralytics`(AGPL-3.0), `torch`, Whisper를 설치하지 않습니다. extra 없이 내장 작업을 부르면 `Task YOLO requires extras("vision")` 오류가 납니다.
        * **실패하면 `null`이나 오류 문자열 대신 예외를 던집니다.** 모든 실패는 `JPyRustException`(`RuntimeException`)입니다. `TaskFailedException`(Python 작업이 예외를 냄), `WorkerTimeoutException`(타임아웃 초과, 워커 재시작), `WorkerCrashedException`(세그폴트 등으로 워커가 죽음). `processImage`, `processAudio`, `processEdgeDetection`, `processNlp`, `processRegression`, `sendTask` 모두 해당합니다. `initialize()` 전이나 `close()` 후 호출은 `IllegalStateException`, Python 워커가 뜨지 않으면 `initialize()`가 예외를 던집니다. [README](README_KR.md)의 *오류 처리와 타임아웃*을 참고하세요.
        * **Python은 첫 실행 때 내려받습니다.** macOS·Linux는 더 이상 `uv`나 시스템 Python이 필요 없습니다. 첫 `initialize()`가 포터블 CPython 3.11.18(python-build-standalone)을 내려받아 SHA-256을 검증합니다. Windows는 JAR에 ~400MB Python을 넣지 않고, python.org의 임베디드 Python 3.11.9와 고정된 `pip` wheel을 내려받습니다(둘 다 SHA-256 검증). 내려받은 파일은 `~/.jpyrust/cache`에 캐시돼 인스턴스끼리 함께 씁니다. 네트워크가 없으면 `python(Path)`를 쓰세요.
        * **`plugins/` 디렉터리 로더를 없앴습니다.** JAR에서 풀린 적이 없었습니다. `@task`와 `moduleDir(...)`로 작업을 등록하세요.
        * **YOLO `bbox`가 원본 이미지 픽셀 좌표입니다.** 폭 640px 초과 이미지는 지금까지 줄인 이미지 기준 좌표로 나왔습니다.
        * **로그는 `System.Logger`(`com.jpyrust`)로 남깁니다.** `System.out`/`System.err`를 쓰지 않습니다. 네이티브 로딩과 pip 출력은 `DEBUG`라 기본 설정에선 안 보입니다.
        * **기존 설치는 `pip install`을 한 번 더 실행합니다.** `.installed` 마커에 의존성 목록의 SHA-256을 기록하고, 다르면 다시 설치합니다.
        * **Maven Central 좌표.** `io.github.farmer0010:jpyrust`, `io.github.farmer0010:jpyrust-spring-boot-starter`로 배포합니다. JitPack은 `com.github.farmer0010:JPyRust`를 유지합니다.
    * **기능:** 내 Python 코드 호출. `jpyrust` 모듈의 `@task`로 함수를 등록하고, `moduleDir(Path)`로 모듈 위치를, `requirements(Path)`로 의존성 목록을 지정한 뒤 `call(task, bytes, args)` / `call(task, text)`로 호출합니다. 결과는 바이트, UTF-8 문자열, JSON으로 옵니다.
    * **기능:** `workers(n)`으로 브리지 하나 뒤에 Python 프로세스 `n`개를 띄웁니다. 여러 스레드의 호출이 병렬로 돌아 CPU를 쓰는 Python이 GIL 하나에 묶이지 않습니다. `callAsync(task, bytes, args)`는 `CompletableFuture`를 돌려줍니다.
    * **기능:** `timeouts(startup, task)`, `model(path, confidence)`, `whisperModel(path)`로 `initialize()` 전에 설정합니다. 긴 `initialize(...)` 오버로드도 그대로 동작합니다.
    * **기능:** `python(Path)`로 이미 있는 인터프리터에서 venv를 만듭니다(`python -m venv --system-site-packages`). 모든 OS에서 동작하며 오프라인·사내망·컨테이너 환경용입니다.
    * **기능:** `JPyRustBridge`가 `AutoCloseable`을 구현합니다.
    * **기능:** `jpyrust-spring-boot-starter`가 `jpyrust.*` 설정으로 브리지를 구성하고, `JPyRustTemplate`을 제공하며, Micrometer 타이머 `jpyrust.calls`(태그 `task`, `outcome`)와 Actuator 헬스 체크를 추가합니다.
    * **기능:** `sendTask(taskType, metadata)`로 등록된 임의 작업을 호출하고 결과를 UTF-8 문자열로 받습니다.
    * **기능:** OpenAI Whisper 오디오 전사(`processAudio(data, length, sampleRate)`, `audio` extra). 이제 Windows에서도 됩니다.
    * **기능:** Apple Silicon에서 CUDA·CPU와 함께 MPS(Metal) 디바이스를 지원합니다.
    * **성능:** 모든 호출이 파일이나 호출마다 만드는 세그먼트 대신, 재사용하며 필요할 때 늘어나는(최대 256MB) 공유 메모리 버퍼로 데이터를 주고받습니다. Apple M5에서 16B 왕복이 0.22ms에서 0.016ms로, 1MB가 0.67ms에서 0.19ms로 줄었습니다(p50). 더 큰 출력은 파일로 넘어가므로 출력 크기 제한이 없습니다. Linux에서는 버퍼를 `/dev/shm` 여유 공간의 절반 안으로 유지해, Docker 기본값 64MB에서도 크래시 위험이 없습니다.
    * **수정:** Rust JNI 진입점을 `catch_unwind`로 감싸 네이티브 panic이나 JNI 오류가 JVM을 종료시키지 않고 Java 예외로 옵니다. 호출 도중 `close()`가 네이티브 상태를 해제하는 문제를 막았습니다.
    * **수정:** Python 워커 대기에 실제로 동작하는 타임아웃을 적용했습니다(기동 600초, 작업 300초, 변경 가능). 멈추거나 죽은 워커는 종료되고 다음 호출 때 다시 뜹니다. 기존 기동 타임아웃은 한 번도 동작하지 않았습니다.
    * **수정:** `initialize(...)`에 넘긴 `modelPath`/`confidence`/Whisper 모델이 Python에 전달됩니다. 지금까지는 무시됐습니다.
    * **수정:** `numpy`/`opencv`가 없어도 Python 워커가 뜨고, 이를 쓰는 작업만 실패합니다. Windows 의존성 설치 실패는 무시되지 않고 `initialize()`가 예외를 던집니다.
    * **수정 (demo-web):** 이미지·웹캠 페이지가 YOLO 검출 결과를 캔버스에 그립니다. `/api/plugin/MATH_ADD`는 `demo-web/python/tasks/`의 `@task` 모듈을 실행합니다.
    * **보안:** Windows 공유 메모리 매핑 권한을 Everyone에서 소유 사용자와 SYSTEM(`D:P(A;;GA;;;OW)(A;;GA;;;SY)`)으로 제한했습니다.
    * **빌드:** `rust-bridge` 버전을 라이브러리와 맞추고, `Cargo.toml`과 `gradle.properties`가 다르면 CI가 실패합니다. JAR에 정적으로 링크된 Rust 크레이트 고지문 `META-INF/THIRD_PARTY_NOTICES.md`를 넣었습니다. Maven Central 아티팩트는 sources·javadoc JAR과 서명된 POM을 포함합니다.
    * **문서:** 상주 Python HTTP 서버와 비교한 JMH 벤치마크([BENCHMARKS.md](BENCHMARKS.md)), [API 문서](API.md), [지원 매트릭스와 버전 정책](COMPATIBILITY.md), 실행 가능한 `examples/quickstart`·`examples/spring-boot`를 추가했습니다.

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
