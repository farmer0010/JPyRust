package com.jpyrust;

import java.io.File;
import java.io.PrintWriter;

public class JPyRustBridge {
    // 1. Native Method 선언
    public native String hello();

    public native void initPython(String pythonHome);

    public native String runPythonAI(String input, int number);

    public native String runPythonRaw(java.nio.ByteBuffer data, int length, int width, int height, int channels);

    // 2. 초기화 상태 플래그
    private static boolean isInitialized = false;

    /**
     * 외부에서 호출 가능한 초기화 메서드 (Zero-Config)
     * Spring Boot 등 서버 시작 시 호출 권장
     */
    public synchronized static void init() {
        if (isInitialized) {
            System.out.println("[JPyRust] Already initialized.");
            return;
        }

        try {
            System.out.println("=== JPyRust Zero-Config Initialization ===");

            // [Step 1] Python Runtime 추출 (from resources/python_dist.zip)
            System.out.println("[Init] Extracting Python Runtime...");
            java.nio.file.Path pythonHome = NativeLoader.extractZip("/python_dist.zip", "jpyrust_python_");
            System.out.println("[Init] Python extracted to: " + pythonHome.toAbsolutePath());

            // [Debug] 파일 목록 및 존재 여부 확인 -> 파일로 작성 (Optional)
            File debugFile = new File("debug_listing.txt");
            try (PrintWriter pw = new PrintWriter(debugFile)) {
                pw.println("Extracted Path: " + pythonHome.toAbsolutePath());
                File[] files = pythonHome.toFile().listFiles();
                if (files != null) {
                    pw.println("Files count: " + files.length);
                }
                pw.println("ai_worker.py exists? " + pythonHome.resolve("ai_worker.py").toFile().exists());
                pw.println("python3.dll exists? " + pythonHome.resolve("python3.dll").toFile().exists());
            } catch (Exception e) {
                // Ignore debug file write error
            }

            // [Step 2] Python DLL 미리 로드 (의존성 해결)
            try {
                // python3.dll은 종종 필수적인 redirection DLL임
                System.load(pythonHome.resolve("python3.dll").toAbsolutePath().toString());
                // 실제 버전별 DLL (예: python310.dll)
                // 버전 바뀌면 수정 필요. 현재 3.10.11
                System.load(pythonHome.resolve("python310.dll").toAbsolutePath().toString());
                System.out.println("[Init] Python DLLs loaded successfully.");
            } catch (UnsatisfiedLinkError | Exception e) {
                System.err.println("[Warning] Failed to load Python DLLs explicitly. " + e.getMessage());
            }

            // [Step 3] Rust Bridge 로드
            System.out.println("[Init] Loading Rust Bridge...");
            NativeLoader.load("rust_bridge");

            // [Step 4] Rust 측 초기화 (PYTHONHOME 등 설정)
            // 인스턴스를 하나 만들어서 호출 (native initPython은 인스턴스 메서드)
            JPyRustBridge bridge = new JPyRustBridge();
            bridge.initPython(pythonHome.toAbsolutePath().toString());
            System.out.println("[Init] Rust Bridge Initialized.\n");

            isInitialized = true;
            System.out.println("=== Initialization Complete ===");

        } catch (Exception e) {
            System.err.println("\n[FATAL] Initialization failed.");
            e.printStackTrace();
            throw new RuntimeException("JPyRust initialization failed", e);
        }
    }

    /**
     * 벤치마크: Zero-Copy vs (Implicit JSON/String Copy)
     */
    public void benchmarkRaw() {
        System.out.println("\n=== Zero-Copy Benchmark ===");
        int size = 1024 * 1024 * 10; // 10MB
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(size);

        // Fill buffer with some data
        for (int i = 0; i < 100; i++) {
            buffer.put(i, (byte) i);
        }

        long start = System.nanoTime();
        String result = runPythonRaw(buffer, size, 0, 0, 0);
        long end = System.nanoTime();

        System.out.println("Result: " + result);
        System.out.println("Time taken (10MB): " + (end - start) / 1_000_000.0 + " ms");
    }

    // 3. 메인 함수 (테스트 & 실행 진입점)
    public static void main(String[] args) {
        // 초기화 호출
        init();

        try {
            JPyRustBridge bridge = new JPyRustBridge();

            // [Step 5] 동작 테스트
            System.out.println("Java: Rust hello() 호출...");
            System.out.println("Rust: " + bridge.hello());

            System.out.println("\nJava: Python AI Worker 호출...");
            String aiResult = bridge.runPythonAI("Antigravity Desert Test", 99);
            System.out.println("Python: " + aiResult);

            // [Step 6] Zero-Copy 벤치마크
            bridge.benchmarkRaw();

            System.out.println("\n=== Success ===");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
