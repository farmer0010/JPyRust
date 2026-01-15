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
            // 1. Detect OS and determine file extension
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String extension;
            if (os.contains("win")) {
                extension = ".dll";
            } else if (os.contains("mac")) {
                extension = ".dylib";
            } else {
                extension = ".so"; // Default to Linux/Unix
            }

            String filename = libName + extension;
            // Native libraries should be placed in /natives/ inside the JAR/classpath
            String resourcePath = "/natives/" + filename;

            // 2. Extract resource to temp file
            InputStream is = NativeLoader.class.getResourceAsStream(resourcePath);
            if (is == null) {
                throw new FileNotFoundException("Native library not found in classpath: " + resourcePath);
            }

            Path tempPath = Files.createTempFile("jpyrust_" + libName + "_", extension);
            tempPath.toFile().deleteOnExit();

            try {
                Files.copy(is, tempPath, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                is.close();
            }

            // 3. Load the library
            System.load(tempPath.toAbsolutePath().toString());
            // Debug log (optional, can be removed in production)
            // System.out.println("[NativeLoader] Loaded: " + tempPath.toAbsolutePath());

        } catch (IOException e) {
            throw new RuntimeException("Failed to load native library: " + libName, e);
        }
    }

    /**
     * Extracts a ZIP resource to a temporary directory.
     * 
     * @param resourcePath Path to the ZIP file in resources (e.g.,
     *                     "/python_dist.zip")
     * @param prefix       Prefix for the temporary directory
     * @return Path to the extracted directory
     */
    public static Path extractZip(String resourcePath, String prefix) {
        try {
            Path targetDir = Files.createTempDirectory(prefix);
            targetDir.toFile().deleteOnExit(); // Note: This might not delete non-empty dirs on some OS

            try (InputStream is = NativeLoader.class.getResourceAsStream(resourcePath)) {
                if (is == null) {
                    throw new FileNotFoundException("Resource not found: " + resourcePath);
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
            }
            return targetDir;
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract ZIP resource: " + resourcePath, e);
        }
    }
}
