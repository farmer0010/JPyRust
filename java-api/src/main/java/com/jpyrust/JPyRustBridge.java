package com.jpyrust;

public class JPyRustBridge {
    // 1. Rust 라이브러리를 로드 (임시로 절대 경로 또는 시스템 경로 사용 가정)
    // 실제 로딩 로직은 나중에 NativeLoader로 대체될 예정임.
    static {
        try {
            // 1. Zero-Config: Load native library from resources (JAR/classpath)
            NativeLoader.load("rust_bridge");
        } catch (Exception e) {
            System.err.println("Native library load failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // 2. Native Method 선언
    public native String hello();

    public native String runPythonAI(String input, int number);

    // 3. 간단한 테스트 메인 함수
    public static void main(String[] args) {
        JPyRustBridge bridge = new JPyRustBridge();
        System.out.println("Java: Rust 브릿지 호출 시도...");
        System.out.println("Rust 응답: " + bridge.hello());

        System.out.println("\nJava: Python AI Worker 호출 시도...");
        try {
            String aiResult = bridge.runPythonAI("Antigravity Test", 42);
            System.out.println("Python 응답: " + aiResult);
        } catch (Exception e) {
            System.err.println("Python 호출 실패: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
