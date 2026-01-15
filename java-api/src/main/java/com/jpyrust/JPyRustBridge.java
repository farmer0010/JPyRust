package com.jpyrust;

import java.io.File;
import java.io.PrintWriter;

public class JPyRustBridge {
    // 1. Native Method 선언
    public native String hello();

    public native void initPython(String pythonHome);

    public native String runPythonAI(String input, int number);

    // 2. 메인 함수 (테스트 & 실행 진입점)
    public static void main(String[] args) {
        try {
            System.out.println("=== JPyRust Zero-Config Start ===");

            // [Step 1] Python Runtime 추출 (from resources/python_dist.zip)
            System.out.println("[Init] Extracting Python Runtime...");
            java.nio.file.Path pythonHome = NativeLoader.extractZip("/python_dist.zip", "jpyrust_python_");
            System.out.println("[Init] Python extracted to: " + pythonHome.toAbsolutePath());

            // [Debug] 파일 목록 및 존재 여부 확인 -> 파일로 작성
            File debugFile = new File("debug_listing.txt");
            try (PrintWriter pw = new PrintWriter(debugFile)) {
                pw.println("Extracted Path: " + pythonHome.toAbsolutePath());
                File[] files = pythonHome.toFile().listFiles();
                if (files != null) {
                    pw.println("Files count: " + files.length);
                    for (File f : files) {
                        pw.println(" - " + f.getName());
                    }
                } else {
                    pw.println("Directory is empty or IO Error.");
                }
                pw.println("ai_worker.py exists? " + pythonHome.resolve("ai_worker.py").toFile().exists());
                pw.println("python3.dll exists? " + pythonHome.resolve("python3.dll").toFile().exists());
            } catch (Exception e) {
                System.err.println("Failed to write debug file: " + e);
            }

            // [Step 2] Python DLL 미리 로드 (의존성 해결)
            // Windows Embeddable Python에는 python3.dll, python310.dll 등이 있음.
            // 이를 미리 로드해야 rust_bridge가 로드될 때 에러가 안 남.
            try {
                // python3.dll은 종종 필수적인 redirection DLL임
                System.load(pythonHome.resolve("python3.dll").toAbsolutePath().toString());
                // 실제 버전별 DLL (예: python310.dll)
                // 파일명을 정확히 모르니, 폴더 내의 python3*.dll을 찾아서 로드하면 더 좋음.
                // 일단 하드코딩 (3.10.11 기준)
                System.load(pythonHome.resolve("python310.dll").toAbsolutePath().toString());
                System.out.println("[Init] Python DLLs loaded successfully.");
            } catch (UnsatisfiedLinkError | Exception e) {
                System.err.println("[Warning] Failed to load Python DLLs explicitly. " + e.getMessage());
            }

            // [Step 3] Rust Bridge 로드
            System.out.println("[Init] Loading Rust Bridge...");
            NativeLoader.load("rust_bridge");

            // [Step 4] Bridge 객체 생성 및 연결
            JPyRustBridge bridge = new JPyRustBridge();

            // Rust 측 초기화 (PYTHONHOME 등 설정)
            bridge.initPython(pythonHome.toAbsolutePath().toString());
            System.out.println("[Init] Rust Bridge Initialized.\n");

            // [Step 5] 동작 테스트
            System.out.println("Java: Rust hello() 호출...");
            System.out.println("Rust: " + bridge.hello());

            System.out.println("\nJava: Python AI Worker 호출...");
            String aiResult = bridge.runPythonAI("Antigravity Desert Test", 99);
            System.out.println("Python: " + aiResult);

            System.out.println("\n=== Success ===");

        } catch (Exception e) {
            System.err.println("\n[FATAL] Execution failed.");
            e.printStackTrace();
            // System.exit(1);
        }
    }
}
