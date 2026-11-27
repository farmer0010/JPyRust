package example;

import com.jpyrust.JPyRustBridge;
import com.jpyrust.TaskFailedException;
import java.nio.ByteBuffer;
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

            ByteBuffer samples = ByteBuffer.allocate(8 * 10_000);
            for (int i = 0; i < 10_000; i++) {
                samples.putDouble(Math.sin(i) * 100);
            }
            System.out.println("STATS  " + new String(python.call("STATS", samples.array(), ""), StandardCharsets.UTF_8));

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
