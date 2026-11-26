package com.jpyrust.demo;

import com.jpyrust.JPyRustBridge;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.ByteBuffer;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai")
public class AIImageController {

    private final JPyRustBridge bridge;

    public AIImageController(JPyRustBridge bridge) {
        this.bridge = bridge;
    }

    @PostMapping(value = "/process-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> processImage(@RequestParam("file") MultipartFile file) {
        String requestId = UUID.randomUUID().toString();
        try {
            byte[] encoded = file.getBytes();
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(encoded.length);
            directBuffer.put(encoded);
            directBuffer.flip();

            byte[] resultData = bridge.processImage(directBuffer, encoded.length, 0, 0, 3, requestId);
            return ResponseEntity.ok(resultData);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping(value = "/edge-detection", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> processEdgeDetection(@RequestParam("file") MultipartFile file) {
        try {
            BufferedImage inputImage = ImageIO.read(file.getInputStream());
            if (inputImage == null)
                return ResponseEntity.badRequest().build();

            BufferedImage bgrImage = new BufferedImage(inputImage.getWidth(), inputImage.getHeight(),
                    BufferedImage.TYPE_3BYTE_BGR);
            bgrImage.getGraphics().drawImage(inputImage, 0, 0, null);

            byte[] pixelData = ((DataBufferByte) bgrImage.getRaster().getDataBuffer()).getData();
            byte[] resultData = bridge.processEdgeDetection(pixelData, bgrImage.getWidth(), bgrImage.getHeight(), 3);

            if (resultData == null)
                return ResponseEntity.internalServerError().build();
            return ResponseEntity.ok(resultData);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}
