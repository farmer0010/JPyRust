package com.jpyrust.bench;

import com.jpyrust.JPyRustBridge;
import com.jpyrust.NDArray;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
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
public class ArrayBenchmark {

    @Param({"1000", "100000", "1000000"})
    int length;

    JPyRustBridge bridge;
    Process server;
    HttpClient http;
    URI scaleJson;
    float[] values;

    @Setup
    public void setup() throws Exception {
        bridge = BenchEnv.bridge(1);
        server = BenchEnv.httpServer();
        scaleJson = BenchEnv.baseUri(server).resolve("/scale_json");
        http = HttpClient.newHttpClient();
        values = new float[length];
        for (int i = 0; i < length; i++) {
            values[i] = i * 0.5f;
        }
    }

    @TearDown
    public void tearDown() {
        bridge.close();
        server.destroy();
    }

    @Benchmark
    public float[] jpyrustNdarray() {
        return bridge.call("SCALE", NDArray.of(values), "").toFloatArray();
    }

    @Benchmark
    public float[] pythonHttpServerJson() throws Exception {
        StringBuilder json = new StringBuilder(length * 10).append('[');
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(values[i]);
        }
        byte[] response = BenchEnv.post(http, scaleJson, json.append(']').toString().getBytes(StandardCharsets.UTF_8));
        String body = new String(response, StandardCharsets.UTF_8);
        float[] out = new float[length];
        int start = 1;
        for (int i = 0; i < length; i++) {
            int end = body.indexOf(i == length - 1 ? ']' : ',', start);
            out[i] = Float.parseFloat(body.substring(start, end).trim());
            start = end + 1;
        }
        return out;
    }
}
