package example;

import com.jpyrust.JPyRustBridge;
import com.jpyrust.NDArray;
import com.jpyrust.TaskFailedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

public class Quickstart {

    public static void main(String[] args) throws Exception {
        try (JPyRustBridge python = new JPyRustBridge("quickstart")
                .requirements(Path.of("python/requirements.txt"))
                .moduleDir(Path.of("python/tasks"))
                .workers(4)) {
            python.initialize();

            double[] samples = new double[10_000];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = Math.sin(i) * 100;
            }
            NDArray series = NDArray.of(samples);
            System.out.println("STATS  " + new String(python.call("STATS", series.toNpy(), ""), StandardCharsets.UTF_8));

            NDArray smoothed = python.call("SMOOTH", series, "50");
            System.out.printf("SMOOTH %s, first value %.4f%n", smoothed, smoothed.toDoubleArray()[0]);

            long started = System.nanoTime();
            List<CompletableFuture<byte[]>> calls = IntStream.range(0, 8)
                    .mapToObj(i -> python.callAsync("PRIMES", new byte[0], "3000000"))
                    .toList();
            calls.forEach(CompletableFuture::join);
            System.out.printf("PRIMES 8 calls on 4 workers in %d ms -> %s primes below 3,000,000%n",
                    (System.nanoTime() - started) / 1_000_000, new String(calls.get(0).join(), StandardCharsets.UTF_8));

            try {
                python.call("FAIL", new byte[0], "bad-input");
            } catch (TaskFailedException e) {
                System.out.println("FAIL   " + e.getMessage());
            }
        }
    }
}
