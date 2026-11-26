package com.jpyrust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

public class NativeLoaderTest {

    @Test
    public void normalizesX86Aliases() {
        assertEquals("x86_64", NativeLoader.normalizeArch("amd64"));
        assertEquals("x86_64", NativeLoader.normalizeArch("x86_64"));
        assertEquals("x86_64", NativeLoader.normalizeArch("X64"));
    }

    @Test
    public void normalizesArmAliases() {
        assertEquals("aarch64", NativeLoader.normalizeArch("aarch64"));
        assertEquals("aarch64", NativeLoader.normalizeArch("arm64"));
    }

    @Test
    public void keepsUnknownArchitectures() {
        assertEquals("ppc64le", NativeLoader.normalizeArch("ppc64le"));
    }

    @Test
    public void currentPlatformLibraryIsBundled() {
        String os = System.getProperty("os.name").toLowerCase();
        String osDir = os.contains("win") ? "windows" : os.contains("mac") ? "darwin" : "linux";
        String ext = os.contains("win") ? ".dll" : os.contains("mac") ? ".dylib" : ".so";
        String path = "/natives/" + osDir + "-" + NativeLoader.normalizeArch(System.getProperty("os.arch"))
                + "/jpyrust" + ext;
        assertNotNull(NativeLoader.class.getResource(path), "missing bundled native: " + path);
    }
}
