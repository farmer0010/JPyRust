package com.jpyrust.demo;

import com.jpyrust.JPyRustBridge;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@RestController
@RequestMapping("/api/ai")
public class AIImageController {

    @PostMapping(value = "/process-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> processImage(@RequestParam("file") MultipartFile file) {
        try {
            // 1. Decode Image
            BufferedImage inputImage = ImageIO.read(file.getInputStream());
            if (inputImage == null) {
                return ResponseEntity.badRequest().build();
            }

            // Convert to 3-Channel BGR (Standard for OpenCV)
            BufferedImage bgrImage = new BufferedImage(inputImage.getWidth(), inputImage.getHeight(),
                    BufferedImage.TYPE_3BYTE_BGR);
            bgrImage.getGraphics().drawImage(inputImage, 0, 0, null);

            int width = bgrImage.getWidth();
            int height = bgrImage.getHeight();
            int channels = 3;
            byte[] pixelData = ((DataBufferByte) bgrImage.getRaster().getDataBuffer()).getData();

            // 2. Prepare DirectByteBuffer (Zero-Copy Interface)
            // Note: We copy valid pixels to DirectBuffer once.
            // True zero-copy from Socket is harder in Java, so we simulate zero-copy
            // *Processing*.
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(pixelData.length);
            directBuffer.order(ByteOrder.nativeOrder());
            directBuffer.put(pixelData);
            directBuffer.flip(); // Prepare for reading by Rust? No, converted to pointer.

            // 3. Call JPyRust (Zero-Copy)
            JPyRustBridge bridge = new JPyRustBridge();
            long start = System.nanoTime();
            String log = bridge.runPythonRaw(directBuffer, pixelData.length, width, height, channels);
            long end = System.nanoTime();

            System.out.println("[AIImageController] " + log);
            System.out.println(
                    "[Benchmark] Zero-copy processing time: " + (end - start) / 1_000_000.0 + " ms (excluding IO)");

            // 4. Retrieve processed data (Write-back confirmed)
            // The python side modified the memory of directBuffer.
            // We copy it back to BufferedImage to encode as PNG.
            directBuffer.position(0);
            directBuffer.get(pixelData); // Update array with processed bytes

            // 5. Encode to PNG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bgrImage, "png", baos);

            return ResponseEntity.ok(baos.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}
