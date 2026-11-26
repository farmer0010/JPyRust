package com.jpyrust;

import java.io.*;
import java.nio.file.*;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public class NativeLoader {

    public static void load(String libName) {
        try {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String extension;
            String osDir;
            if (os.contains("win")) {
                extension = ".dll";
                osDir = "windows";
            } else if (os.contains("mac")) {
                extension = ".dylib";
                osDir = "darwin";
            } else {
                extension = ".so";
                osDir = "linux";
            }

            String filename = libName + extension;
            String platformDir = osDir + "-" + normalizeArch(System.getProperty("os.arch"));

            String[] resourcePaths = {
                    "/natives/" + platformDir + "/" + filename,
                    "natives/" + platformDir + "/" + filename,
                    "/BOOT-INF/classes/natives/" + platformDir + "/" + filename,
                    filename
            };

            InputStream is = null;
            String foundPath = null;

            for (String path : resourcePaths) {
                System.err.println("[NativeLoader] Checking path: " + path);
                is = NativeLoader.class.getResourceAsStream(path);
                if (is != null) {
                    foundPath = path;
                    System.err.println("[NativeLoader] Found with class loader at: " + path);
                    break;
                }
            }

            if (is == null) {
                ClassLoader contextCL = Thread.currentThread().getContextClassLoader();
                if (contextCL != null) {
                    for (String path : resourcePaths) {
                        String adjustedPath = path.startsWith("/") ? path.substring(1) : path;
                        System.err.println("[NativeLoader] Checking context CL path: " + adjustedPath);
                        is = contextCL.getResourceAsStream(adjustedPath);
                        if (is != null) {
                            foundPath = adjustedPath;
                            System.err.println("[NativeLoader] Found with context classloader at: " + adjustedPath);
                            break;
                        }
                    }
                }
            }

            if (is == null) {
                String msg = "Native library not found in classpath: " + filename + " (tried: "
                        + String.join(", ", resourcePaths) + ")";
                System.err.println("[NativeLoader] " + msg);
                throw new FileNotFoundException(msg);
            }

            Path tempPath = Files.createTempFile("jpyrust_" + libName + "_", extension);
            tempPath.toFile().deleteOnExit();

            try {
                Files.copy(is, tempPath, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                is.close();
            }

            System.err.println("[NativeLoader] Loading from temp: " + tempPath.toAbsolutePath());
            System.load(tempPath.toAbsolutePath().toString());
            System.err.println("[NativeLoader] Successfully loaded: " + libName);

        } catch (Throwable e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to load native library: " + libName, e);
        }
    }

    static String normalizeArch(String arch) {
        String a = arch.toLowerCase(Locale.ROOT);
        if (a.equals("amd64") || a.equals("x86_64") || a.equals("x64")) {
            return "x86_64";
        }
        if (a.equals("aarch64") || a.equals("arm64")) {
            return "aarch64";
        }
        return a;
    }

    public static Path extractZip(String resourcePath, String prefix) {
        try {
            Path targetDir = Files.createTempDirectory(prefix);
            targetDir.toFile().deleteOnExit();
            return extractZip(resourcePath, targetDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp directory with prefix: " + prefix, e);
        }
    }

    public static void extractFile(String resourcePath, Path targetFile) {
        try {
            InputStream is = NativeLoader.class.getResourceAsStream(resourcePath);
            if (is == null) {
                throw new FileNotFoundException("Resource not found: " + resourcePath);
            }
            if (targetFile.getParent() != null) {
                Files.createDirectories(targetFile.getParent());
            }
            try (is) {
                Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract resource: " + resourcePath, e);
        }
    }

    public static Path extractZip(String resourcePath, Path targetDir) {
        try (InputStream is = NativeLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new FileNotFoundException("Resource not found: " + resourcePath);
            }
            return unzip(is, targetDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract ZIP resource: " + resourcePath, e);
        }
    }

    public static Path extractZip(Path zipFile, Path targetDir) {
        try (InputStream is = Files.newInputStream(zipFile)) {
            return unzip(is, targetDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract ZIP file: " + zipFile, e);
        }
    }

    private static Path unzip(InputStream is, Path targetDir) throws IOException {
        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
        }

        try (ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path newPath = targetDir.resolve(entry.getName()).normalize();
                if (!newPath.startsWith(targetDir)) {
                    throw new IOException("Zip entry is outside of the target dir: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    if (newPath.getParent() != null) {
                        Files.createDirectories(newPath.getParent());
                    }
                    try (OutputStream os = Files.newOutputStream(newPath)) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
            }
        }
        return targetDir;
    }
}
