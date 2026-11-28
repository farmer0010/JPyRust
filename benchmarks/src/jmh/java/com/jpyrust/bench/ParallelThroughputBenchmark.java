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
import org.openjdk.jmh.annotations.Threads;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(4)
public class ParallelThroughputBenchmark {

    static final String LOOPS = "200000";
    static final byte[] EMPTY = new byte[0];

    @Param({"1", "4"})
    int workers;

    JPyRustBridge bridge;
    Process server;
    HttpClient http;
    URI spin;

    @Setup
    public void setup() throws Exception {
        bridge = BenchEnv.bridge(workers);
        server = BenchEnv.httpServer();
        spin = BenchEnv.baseUri(server).resolve("/spin/" + LOOPS);
        http = HttpClient.newHttpClient();
    }

    @TearDown
    public void tearDown() {
        bridge.close();
        server.destroy();
    }

    @Benchmark
    public byte[] jpyrust() {
        return bridge.call("SPIN", EMPTY, LOOPS);
    }

    @Benchmark
    public byte[] pythonThreadingHttpServer() throws Exception {
        return BenchEnv.post(http, spin, EMPTY);
    }
}
