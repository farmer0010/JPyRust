package com.jpyrust.bench;

import com.jpyrust.JPyRustBridge;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class BenchEnv {

    static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"), "jpyrust-bench");
    static final Path WORK = ROOT.resolve("work");

    private BenchEnv() {
    }

    static JPyRustBridge bridge(int workers) throws Exception {
        Path tasks = Files.createDirectories(ROOT.resolve("tasks"));
        copy("bench_tasks.py", tasks.resolve("bench_tasks.py"));
        Path requirements = Files.writeString(ROOT.resolve("requirements.txt"), "");
        JPyRustBridge bridge = new JPyRustBridge("bench").requirements(requirements).moduleDir(tasks).workers(workers);
        bridge.initialize(WORK.toString());
        return bridge;
    }

    static Process httpServer() throws Exception {
        Path script = ROOT.resolve("http_server.py");
        copy("http_server.py", script);
        Path python = System.getProperty("os.name").toLowerCase().contains("win")
                ? WORK.resolve("python_dist/python.exe")
                : WORK.resolve("venv/bin/python3");
        return new ProcessBuilder(python.toString(), script.toString()).redirectErrorStream(true).start();
    }

    static URI baseUri(Process server) throws Exception {
        String port = new BufferedReader(new InputStreamReader(server.getInputStream())).readLine();
        return URI.create("http://127.0.0.1:" + port.trim());
    }

    static byte[] post(HttpClient client, URI uri, byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    private static void copy(String resource, Path target) throws Exception {
        try (InputStream in = BenchEnv.class.getResourceAsStream("/bench_tasks/" + resource)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
