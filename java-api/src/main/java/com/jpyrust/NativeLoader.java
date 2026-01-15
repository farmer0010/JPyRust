package com.jpyrust;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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
}
