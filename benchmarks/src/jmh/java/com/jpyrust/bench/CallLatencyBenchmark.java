package com.jpyrust.bench;

import com.jpyrust.JPyRustBridge;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CallLatencyBenchmark {

    @Param({"16", "65536", "1048576"})
    int size;

    JPyRustBridge bridge;
    Process server;
    HttpClient http;
    URI echo;
    byte[] payload;

    @Setup
    public void setup() throws Exception {
        bridge = BenchEnv.bridge(1);
        server = BenchEnv.httpServer();
        echo = BenchEnv.baseUri(server).resolve("/echo");
        http = HttpClient.newHttpClient();
        payload = new byte[size];
    }

    @TearDown
    public void tearDown() {
        bridge.close();
        server.destroy();
    }

    @Benchmark
    public byte[] jpyrust() {
        return bridge.call("ECHO", payload, "");
    }

    @Benchmark
    public byte[] pythonHttpServer() throws Exception {
        return BenchEnv.post(http, echo, payload);
    }
}
