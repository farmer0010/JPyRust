package com.jpyrust.demo;

import com.jpyrust.JPyRustBridge;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai")
public class AIImageController {

    @Value("${app.ai.work-dir}")
    private String workDir;

    @Value("${app.ai.source-script-dir}")
    private String sourceScriptDir;

    @PostConstruct
    public void init() {
        System.out.println("[AIImageController] Work Directory: " + workDir);
        System.out.println("[AIImageController] Source Script Dir: " + sourceScriptDir);

        // Ensure work directory exists
        File dir = new File(workDir);
        if (!dir.exists()) {
            dir.mkdirs();
            System.out.println("[AIImageController] Created work directory: " + workDir);
        }

        // Initialize Rust Bridge with configurable paths
        JPyRustBridge.initialize(workDir, sourceScriptDir);
    }

    @PostMapping(value = "/process-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> processImage(@RequestParam("file") MultipartFile file) {
        // Generate unique request ID for this request (prevents file collisions)
        String requestId = UUID.randomUUID().toString();
        System.out.println("[AIImageController] Request Received | ID: " + requestId);

        long tStart = System.nanoTime();
        try {
            // 1. Decode Image
            long t1 = System.nanoTime();
            BufferedImage inputImage = ImageIO.read(file.getInputStream());
            if (inputImage == null) {
                System.out.println("[AIImageController] Invalid Image | ID: " + requestId);
                return ResponseEntity.badRequest().build();
            }
            long tRead = System.nanoTime();
            System.out.println(
                    "[AIImageController] Image Decoded: " + inputImage.getWidth() + "x" + inputImage.getHeight());

            // Convert to 3-Channel BGR (Standard for OpenCV)
            BufferedImage bgrImage = new BufferedImage(inputImage.getWidth(), inputImage.getHeight(),
                    BufferedImage.TYPE_3BYTE_BGR);
            bgrImage.getGraphics().drawImage(inputImage, 0, 0, null);

            int width = bgrImage.getWidth();
            int height = bgrImage.getHeight();
            int channels = 3;
            byte[] pixelData = ((DataBufferByte) bgrImage.getRaster().getDataBuffer()).getData();
            long tConvert = System.nanoTime();

            // 2. Prepare DirectByteBuffer for Rust
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(pixelData.length);
            directBuffer.order(ByteOrder.nativeOrder());
            directBuffer.put(pixelData);
            directBuffer.flip();
            long tAlloc = System.nanoTime();

            // 3. Call Rust Bridge with unique request ID
            System.out.println("[AIImageController] Calling Rust Bridge...");
            JPyRustBridge bridge = new JPyRustBridge();
            byte[] jpegData = bridge.processImage(workDir, directBuffer, pixelData.length, width, height, channels,
                    requestId);
            long tBridge = System.nanoTime();

            if (jpegData == null) {
                System.err.println("[AIImageController] Bridge returned null! | ID: " + requestId);
                return ResponseEntity.internalServerError().build();
            }

            // Python already returns JPEG data - no need to re-encode!
            long tEnd = System.nanoTime();

            // Logging
            double totalMs = (tEnd - tStart) / 1_000_000.0;
            double readMs = (tRead - t1) / 1_000_000.0;
            double convertMs = (tConvert - tRead) / 1_000_000.0;
            double allocMs = (tAlloc - tConvert) / 1_000_000.0;
            double bridgeMs = (tBridge - tAlloc) / 1_000_000.0;

            System.out.println(String.format(
                    "[Timing] ID: %s | Total: %.0fms | Bridge: %.0f | JPEG: %.1fKB",
                    requestId.substring(0, 8), totalMs, bridgeMs, jpegData.length / 1024.0));

            return ResponseEntity.ok(jpegData);

        } catch (Exception e) {
            System.out.println("[AIImageController] Error | ID: " + requestId + " | " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}
