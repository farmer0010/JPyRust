# JPyRust 사용자 가이드

Java 애플리케이션에서 JPyRust를 설치하고, 초기화하고, 호출하는 실전 가이드입니다. 아키텍처와 벤치마크 수치는 [메인 README](../README.md)를 참고하세요. [English Guide](GUIDE.md).

## 목차

- [요구 사항](#요구-사항)
- [설치](#설치)
- [기본 사용법](#기본-사용법)
- [초기화 옵션](#초기화-옵션)
- [태스크 종류](#태스크-종류)
  - [YOLO 객체 탐지](#yolo-객체-탐지)
  - [엣지 검출](#엣지-검출)
  - [NLP 감성 분석](#nlp-감성-분석)
  - [선형 회귀](#선형-회귀)
- [멀티 인스턴스 사용법](#멀티-인스턴스-사용법)
- [플랫폼별 참고사항](#플랫폼별-참고사항)
- [벤치마크 직접 돌려보기](#벤치마크-직접-돌려보기)
- [문제 해결](#문제-해결)

## 요구 사항

| 항목 | 요구 사항 |
| :--- | :--- |
| Java | 17 이상 |
| 플랫폼 | Windows, macOS, Linux (x86_64) |
| Windows | 별도 준비 불필요 — 임베디드 Python이 번들되어 있고 자동으로 설치됩니다. |
| macOS / Linux | `PATH`에 `python3` 인터프리터가 있어야 합니다(JPyRust는 `python3.12` → `python3.11` → `python3.13` → `python3` 순으로 찾습니다). 찾은 인터프리터로 격리된 전용 venv를 새로 만들어 쓰기 때문에 시스템 Python의 패키지는 건드리지 않습니다. |

## 설치

`build.gradle.kts`에 JitPack 저장소와 의존성을 추가하세요:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.farmer0010:JPyRust:v1.3.1")
}
```

## 기본 사용법

```java
import com.jpyrust.JPyRustBridge;
import java.nio.ByteBuffer;

public class VisionService {
    public void detect(byte[] jpegBytes) {
        JPyRustBridge bridge = new JPyRustBridge("cam1");
        bridge.initialize(); // ~/.jpyrust/cam1 에 워커 프로세스 생성

        ByteBuffer buf = ByteBuffer.allocateDirect(jpegBytes.length);
        buf.put(jpegBytes);
        buf.flip();

        byte[] result = bridge.processImage(buf, jpegBytes.length, 0, 0, 0);
        System.out.println(new String(result)); // {"detections": [...]}

        bridge.close();
    }
}
```

처음 써보시는 분들이 헷갈리기 쉬운 부분들:

- **`initialize()`는 처음 한 번만 느립니다.** 인스턴스 디렉터리별로 첫 호출에서 Python 환경을 구성하고(Windows는 임베디드 배포판, macOS/Linux는 venv) 의존성을 설치하는데, 네트워크/플랫폼에 따라 수 초에서 수 분까지 걸릴 수 있습니다. 이후 실행은 `~/.jpyrust/<instanceId>/`의 `.installed` 마커를 보고 재사용해서 빠릅니다.
- **`processImage`는 인코딩된 이미지**(JPEG/PNG 바이트, 즉 `.jpg` 파일을 그대로 읽거나 `ImageIO`/OpenCV `imencode`로 만든 바이트)를 기대합니다. 원본 픽셀 배열이 아닙니다. 내부적으로 OpenCV `imdecode`로 디코딩하기 때문에 이 태스크에서는 `width`/`height`/`channels` 인자가 실제로 쓰이지 않습니다 — 모르면 `0`을 넘기셔도 됩니다.
- **`ByteBuffer`는 반드시 direct 버퍼**(`ByteBuffer.allocateDirect(...)`)여야 합니다 — 네이티브 쪽이 원시 메모리 주소로 직접 읽기 때문입니다.
- **다 쓰면 항상 `close()`를 호출**하세요. 안 그러면 상시 대기 중인 Python 워커 프로세스가 백그라운드에 계속 남아있습니다.

## 초기화 옵션

```java
// 1. 기본값: model=yolov8n.pt, confidence=0.5, 작업 디렉터리=~/.jpyrust/<instanceId>
bridge.initialize();

// 2. 커스텀 작업 디렉터리, 모델/신뢰도는 기본값
bridge.initialize("/var/lib/myapp/cam1");

// 3. 커스텀 모델 + 신뢰도 임계값 (v1.3.1+)
bridge.initialize("/var/lib/myapp/cam1", "custom_model.pt", 0.25f);

// 4. 공유 메모리 세션 키까지 전부 직접 지정
bridge.initialize("/var/lib/myapp/cam1", "custom_model.pt", 0.25f, "my-session-key");
```

`modelPath`는 Python 쪽 Ultralytics `YOLO(...)` 생성자가 그대로 받는 값입니다 — `.pt` 파일 절대경로거나, Ultralytics가 알아서 받아올 수 있는 모델 이름이면 됩니다. 모델 로드에 실패해도 JPyRust가 죽지는 않습니다 — YOLO 탐지 호출이 그냥 빈 탐지 결과(`{"detections": []}`)를 반환할 뿐입니다.

`initialize()`는 인스턴스당 멱등적입니다: 같은 `JPyRustBridge` 객체에 두 번 호출해도 두 번째 호출은 아무 일도 하지 않습니다.

## 태스크 종류

각 태스크 종류는 `JPyRustBridge`에 각자의 public 메서드가 있습니다. 내부적으로는 전부 같은 네이티브 `executeTask` 호출을 거치고, 상시 대기 데몬이 태스크 이름으로 분기합니다.

### YOLO 객체 탐지

```java
byte[] result = bridge.processImage(directBuffer, length, width, height, channels);
// -> {"detections": [{"bbox": [x, y, w, h], "label": "person", "score": 0.87}, ...]}
```

직접 요청 ID를 지정할 수 있는 오버로드도 있습니다(로그/트레이스 연결에 유용):

```java
byte[] result = bridge.processImage(directBuffer, length, width, height, channels, myRequestId);
```

### 엣지 검출

`processImage`와 달리 이 메서드는 **원본 픽셀 바이트**(인코딩된 JPEG/PNG 아님)를 기대합니다 — `width`/`height`/`channels`로 버퍼를 어떻게 해석할지 알려주면, OpenCV Canny 필터를 돌려서 인코딩된 JPEG를 반환합니다.

```java
byte[] jpegResult = bridge.processEdgeDetection(rawPixelBytes, width, height, channels);
```

### NLP 감성 분석

Python 쪽에서 `pandas`/`scikit-learn`/`TextBlob`으로 처리합니다.

```java
String result = bridge.processNlp("This library saved me a ton of latency!");
// -> "POSITIVE (Polarity: 0.62)"
```

### 선형 회귀

```java
String points = "[[1,2],[2,4],[3,6]]"; // [x, y] 쌍의 배열
String result = bridge.processRegression(points);
// -> "Slope: 2.0000, Intercept: 0.0000"
```

## 멀티 인스턴스 사용법

`JPyRustBridge` 인스턴스는 각각 완전히 독립적입니다 — 각자 자신만의 Python 데몬 프로세스, 작업 디렉터리, 공유 메모리 세션을 가집니다. 여러 카메라 스트림을 병렬로 처리하는 것이 원래 의도된 사용법입니다:

```java
JPyRustBridge cam1 = new JPyRustBridge("cam1");
JPyRustBridge cam2 = new JPyRustBridge("cam2");
cam1.initialize();
cam2.initialize();

// 서로 다른 스레드에서 동시에 호출해도 됩니다 —
// 각자 자신만의 데몬 프로세스와 공유 메모리 세그먼트를 소유합니다.
```

작업 디렉터리는 기본적으로 `~/.jpyrust/<instanceId>`라서, 기본값만 써도 `instanceId`가 다르면 서로 겹치지 않습니다.

## 플랫폼별 참고사항

- **Windows**: 포터블 임베디드 Python 배포판이 JAR 안에 번들되어 있고, 첫 `initialize()` 호출 때 풀립니다. 완전히 독립적으로 동작합니다.
- **macOS / Linux**: 이 플랫폼용 포터블 임베디드 Python은 없어서, 시스템 `python3`을 찾아 그걸로 전용 venv(`~/.jpyrust/<instanceId>/venv`)를 만듭니다. 즉 `python3`이 미리 설치되어 있고 `PATH`에서 찾아져야 합니다.
- 세 플랫폼 모두 대응하는 네이티브 라이브러리(`jpyrust.dll` / `jpyrust.dylib` / `jpyrust.so`)가 JAR 안에 들어있습니다 — 배포된 아티팩트를 쓸 때는 직접 빌드할 필요가 없습니다.

## 벤치마크 직접 돌려보기

이 레포에는 실제로 돌아가는 실측 레이턴시 벤치마크가 포함되어 있습니다.

```bash
./gradlew :java-api:compileTestJava
java -cp java-api/build/classes/java/main:java-api/build/classes/java/test:java-api/build/resources/main \
     com.jpyrust.LatencyBenchmark
```

`sample.png`에 대해 실제 YOLOv8n 추론을 돌려서, 매번 새로 띄우는 콜드 Python subprocess와 상시 대기 SHMEM 데몬을 비교합니다. 어떤 수치가 나오고 왜 그런 모양인지는 README의 [성능 벤치마크 섹션](../README_KR.md#-성능-벤치마크)을 참고하세요.

## 문제 해결

자주 발생하는 문제(네이티브 라이브러리를 못 찾음, Windows 공유 메모리 권한, Python 의존성 문제)와 해결법은 [메인 README](../README_KR.md)의 **설정 및 문제 해결** 섹션을 참고하세요.
