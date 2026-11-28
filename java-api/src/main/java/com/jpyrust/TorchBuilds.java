package com.jpyrust;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TorchBuilds {

    static final String DEFAULT = "default";
    static final String AUTO = "auto";
    static final String CPU = "cpu";
    static final String INDEX = "https://download.pytorch.org/whl/";

    private static final List<String> CUDA_BUILDS = List.of("cu128", "cu126", "cu124", "cu121", "cu118");
    private static final Pattern BUILD = Pattern.compile("default|auto|cpu|cu\\d{3}|rocm\\d+(\\.\\d+)*");
    private static final Pattern DRIVER_CUDA = Pattern.compile("CUDA Version:\\s*(\\d+)\\.(\\d+)");
    private static final Pattern TORCH_LINE = Pattern.compile("^\\s*(torch|torchvision|torchaudio)\\s*([=<>!~;\\[].*)?$");
    private static final Pattern TORCH_PIN = Pattern.compile("^\\s*torch\\s*==\\s*([0-9][^\\s;]*)");

    private TorchBuilds() {
    }

    static String validate(String build) {
        String normalized = build.trim().toLowerCase(Locale.ROOT);
        if (!BUILD.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Unknown torch build: " + build
                    + " (use default, auto, cpu, a CUDA build such as cu121, or a ROCm build such as rocm6.2)");
        }
        return normalized;
    }

    static List<String> torchRequirements(String requirements) {
        List<String> lines = new ArrayList<>();
        for (String line : requirements.split("\\R")) {
            String withoutComment = line.replaceFirst("#.*", "").trim();
            if (TORCH_LINE.matcher(withoutComment).matches()) {
                lines.add(withoutComment);
            }
        }
        return lines;
    }

    static Optional<String> pinnedTorch(List<String> torchLines) {
        for (String line : torchLines) {
            Matcher matcher = TORCH_PIN.matcher(line);
            if (matcher.find()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    static Optional<String> driverCuda(String nvidiaSmiOutput) {
        Matcher matcher = DRIVER_CUDA.matcher(nvidiaSmiOutput);
        return matcher.find() ? Optional.of(matcher.group(1) + "." + matcher.group(2)) : Optional.empty();
    }

    static String recommend(boolean mac, Optional<String> driverCuda, Optional<String> pinnedTorch,
            BiPredicate<String, String> published) {
        if (mac) {
            return DEFAULT;
        }
        if (driverCuda.isEmpty()) {
            return CPU;
        }
        int driver = cudaNumber(driverCuda.get());
        for (String build : CUDA_BUILDS) {
            if (Integer.parseInt(build.substring(2)) <= driver
                    && (pinnedTorch.isEmpty() || published.test(build, pinnedTorch.get()))) {
                return build;
            }
        }
        return CPU;
    }

    static Optional<String> detectDriverCuda() {
        try {
            Process process = new ProcessBuilder("nvidia-smi").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return driverCuda(output);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static int detectGpuCount() {
        try {
            Process process = new ProcessBuilder("nvidia-smi", "-L").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return 0;
            }
            return gpuCount(output);
        } catch (Exception e) {
            return 0;
        }
    }

    static int gpuCount(String nvidiaSmiList) {
        return (int) nvidiaSmiList.lines().filter(line -> line.startsWith("GPU ")).count();
    }

    static boolean published(String build, String torchVersion) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .proxy(ProxySelector.getDefault())
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> page = client.send(
                    HttpRequest.newBuilder(URI.create(INDEX + build + "/torch/")).timeout(Duration.ofSeconds(60)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return page.statusCode() == 200 && (page.body().contains("torch-" + torchVersion + "+" + build + "-")
                    || page.body().contains("torch-" + torchVersion + "%2B" + build + "-"));
        } catch (Exception e) {
            return false;
        }
    }

    private static int cudaNumber(String version) {
        String[] parts = version.split("\\.");
        return Integer.parseInt(parts[0]) * 10 + Integer.parseInt(parts[1]);
    }
}
