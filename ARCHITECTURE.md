# JPyRust Architecture & Guidelines

## 1. Project Vision
**JPyRust**는 Java 애플리케이션에서 Python AI 생태계를 **Zero-Config**로 사용할 수 있게 해주는 고성능 브릿지 라이브러리입니다.
최종 사용자는 Python이나 Rust 컴파일러를 설치할 필요 없이, 오직 **JAR 파일 하나**만 추가하여 기능을 사용해야 합니다.

## 2. Core Architecture Layers
[Java App] -> (JNI) -> [Rust Bridge] -> (PyO3) -> [Embedded Python Interpreter]

### Layer 1: Java API (User-Facing)
- **Role**: 사용자 친화적인 인터페이스 제공, Native Library 로딩 관리.
- **Principle**:
    - `NativeLoader`: OS/Arch를 자동 감지하여 JAR 내부의 `.dll`, `.so`를 임시 폴더로 추출 및 로드.
    - **Exception Handling**: Rust/Python 계층의 에러를 Java Exception으로 변환하여 던짐 (JVM Crash 절대 금지).

### Layer 2: Rust Bridge (The Safety Valve)
- **Role**: JVM과 Python VM 사이의 중재자.
- **Tech Stack**: `jni-rs`, `pyo3`.
- **Principle**:
    - **Memory Safety**: JNI 포인터 오용 방지.
    - **GIL Management**: Python 호출 시 GIL 획득/해제 로직을 명시적으로 관리하여 Deadlock 방지.
    - **Panic Free**: Rust의 Panic이 JNI 경계를 넘어 JVM을 종료시키지 않도록 `catch_unwind` 사용 필수.

### Layer 3: Python Core (The Worker)
- **Role**: 실제 AI/ML 로직 수행.
- **Principle**:
    - **Embedded**: 호스트 시스템의 Python이 아닌, Rust 바이너리에 내장되거나 격리된 Python 환경 사용.
    - **Pure Logic**: I/O나 UI 로직 배제, 순수 데이터 처리 함수 위주 작성.

## 3. Build & Distribution Strategy
- **Build System**: Gradle (Java) + Cargo (Rust).
- **Artifact**: `jpyrust.jar` (내부에 플랫폼별 Native Binary 포함).
- **Cross-Compilation**: CI 환경에서 Windows, Linux, macOS용 바이너리를 모두 빌드하여 JAR에 패키징.

## 4. Development Rules for Agents
1. **No System Dependency**: 코드 작성 시 사용자의 로컬 환경(Python 경로 등)에 의존하는 하드코딩 금지.
2. **Type Safety**: Java와 Rust 간 데이터 교환 시 JSON(Serde) 직렬화를 기본으로 하여 타입 불일치 방지.
3. **Async Awareness**: Python의 긴 작업이 JVM 메인 스레드를 차단하지 않도록 설계.
