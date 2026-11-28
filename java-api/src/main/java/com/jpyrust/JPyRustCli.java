package com.jpyrust;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class JPyRustCli {

    private static final String USAGE = String.join("\n",
            "Usage: java -jar jpyrust.jar <command> [options]",
            "",
            "Commands:",
            "  doctor                 Show this machine's OS, GPU and the recommended settings",
            "  setup [options]        Download Python and install the environment ahead of time",
            "  version                Print the JPyRust version",
            "",
            "Options for setup:",
            "  --instance <id>        Instance id (default: default)",
            "  --work-dir <dir>       Instance directory (default: ~/.jpyrust/<id>)",
            "  --python-version <v>   3.11, 3.12, 3.13 or 3.14",
            "  --python <path>        Build the venv from this interpreter instead",
            "  --torch <build>        default, auto, cpu, cu118, cu121, ...",
            "  --extras <a,b>         vision, audio, ml",
            "  --requirements <file>  pip requirements",
            "  --module-dir <dir>     Task modules to import (repeatable)",
            "",
            "Every option can also be set when the application runs, without code changes:",
            "  -Djpyrust.python-version=3.12  -Djpyrust.torch=auto  -Djpyrust.devices=cuda:0,cuda:1  -Djpyrust.workers=4",
            "  or JPYRUST_PYTHON_VERSION, JPYRUST_TORCH, JPYRUST_DEVICES, JPYRUST_WORKERS");

    private JPyRustCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h") || args[0].equals("help")) {
            out.println(USAGE);
            return args.length == 0 ? 1 : 0;
        }
        try {
            return switch (args[0]) {
                case "doctor" -> doctor(out);
                case "setup" -> setup(Arrays.copyOfRange(args, 1, args.length), out);
                case "version" -> {
                    out.println(version());
                    yield 0;
                }
                default -> {
                    err.println("Unknown command: " + args[0]);
                    err.println(USAGE);
                    yield 1;
                }
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println(e.getMessage());
            return 1;
        } catch (JPyRustException e) {
            err.println(e.getMessage() + (e.getCause() != null ? ": " + e.getCause().getMessage() : ""));
            return 2;
        }
    }

    private static int doctor(PrintStream out) {
        String os = System.getProperty("os.name", "");
        boolean mac = os.toLowerCase(Locale.ROOT).contains("mac");
        String arch = NativeLoader.normalizeArch(System.getProperty("os.arch"));
        Optional<String> cuda = TorchBuilds.detectDriverCuda();
        int gpus = cuda.isPresent() ? TorchBuilds.detectGpuCount() : 0;
        String torch = TorchBuilds.recommend(mac, cuda, Optional.of("2.2.0"), TorchBuilds::published);

        out.println("JPyRust " + version());
        out.printf("  OS        %s %s%n", os, arch);
        out.printf("  Java      %s%n", System.getProperty("java.version"));
        out.printf("  GPU       %s%n", cuda.map(v -> gpus + " NVIDIA GPU(s), driver supports CUDA " + v)
                .orElse(mac ? "Apple GPU through MPS (torch picks it up automatically)" : "no NVIDIA GPU found (nvidia-smi)"));
        out.printf("  Cache     %s%n", cachedPythons());
        out.println();
        out.println("Recommended");
        out.printf("  python-version  3.11 (3.12-3.14 work when you do not use the built-in extras)%n");
        out.printf("  torch           %s%s%n", torch, torch.equals(TorchBuilds.CPU) && !mac
                ? " (no GPU: the CPU build skips gigabytes of CUDA libraries)" : "");
        if (gpus > 1) {
            out.printf("  devices         %s (one worker per GPU)%n",
                    IntStream.range(0, gpus).mapToObj(i -> "cuda:" + i).collect(Collectors.joining(",")));
        }
        out.println();
        out.println("Apply without code changes:");
        out.printf("  java -Djpyrust.torch=%s%s -jar app.jar%n", torch,
                gpus > 1 ? " -Djpyrust.devices=" + IntStream.range(0, gpus).mapToObj(i -> "cuda:" + i)
                        .collect(Collectors.joining(",")) : "");
        out.println("Prepare an environment ahead of time (for example in a Docker build):");
        out.printf("  java -jar jpyrust.jar setup --instance <id> --torch %s --extras vision%n", torch);
        return 0;
    }

    private static int setup(String[] args, PrintStream out) {
        String instance = "default";
        String workDir = null;
        List<String> extras = new ArrayList<>();
        List<Path> moduleDirs = new ArrayList<>();
        String pythonVersion = null;
        String torch = null;
        Path python = null;
        Path requirements = null;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            String value = args[++i];
            switch (option) {
                case "--instance" -> instance = value;
                case "--work-dir" -> workDir = value;
                case "--python-version" -> pythonVersion = value;
                case "--python" -> python = Path.of(value);
                case "--torch" -> torch = value;
                case "--extras" -> extras.addAll(Arrays.stream(value.split(",")).map(String::trim)
                        .filter(name -> !name.isEmpty()).toList());
                case "--requirements" -> requirements = Path.of(value);
                case "--module-dir" -> moduleDirs.add(Path.of(value));
                default -> throw new IllegalArgumentException("Unknown option: " + option + "\n\n" + USAGE);
            }
        }

        JPyRustBridge bridge = new JPyRustBridge(instance).extras(extras.toArray(String[]::new));
        if (pythonVersion != null) {
            bridge.pythonVersion(pythonVersion);
        }
        if (torch != null) {
            bridge.torch(torch);
        }
        if (python != null) {
            bridge.python(python);
        }
        if (requirements != null) {
            bridge.requirements(requirements);
        }
        moduleDirs.forEach(bridge::moduleDir);
        String directory = workDir != null ? workDir
                : Path.of(System.getProperty("user.home"), ".jpyrust", instance).toString();

        long started = System.nanoTime();
        try (bridge) {
            bridge.initialize(directory);
            out.printf("Ready in %.1f s%n", (System.nanoTime() - started) / 1e9);
            out.println("  instance  " + instance);
            out.println("  directory " + directory);
            out.println("  torch     " + bridge.torchBuild());
            out.println("  worker    " + bridge.sendTask("STATUS", "NONE"));
        }
        return 0;
    }

    private static String cachedPythons() {
        Path cache = Path.of(System.getProperty("user.home"), ".jpyrust", "cache");
        try (var entries = Files.list(cache)) {
            String names = entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("cpython-") && !name.endsWith(".tar.gz"))
                    .map(name -> name.substring("cpython-".length(), name.indexOf('+')))
                    .sorted()
                    .collect(Collectors.joining(", "));
            return names.isEmpty() ? "no Python downloaded yet" : "Python " + names + " in " + cache;
        } catch (Exception e) {
            return "no Python downloaded yet";
        }
    }

    private static String version() {
        String version = JPyRustCli.class.getPackage().getImplementationVersion();
        return version != null ? version : "development build";
    }
}
