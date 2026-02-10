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

    @Value("${app.ai.model-path:yolov8n.pt}")
    private String modelPath;

    @Value("${app.ai.confidence:0.5}")
    private float confidence;

    @PostConstruct
    public void init() {
        System.out.println("[AIImageController] Work Directory: " + workDir);
        System.out.println("[AIImageController] Source Script Dir: " + sourceScriptDir);

        File dir = new File(workDir);
        if (!dir.exists()) {
            dir.mkdirs();
            System.out.println("[AIImageController] Created work directory: " + workDir);
        }

        JPyRustBridge.initialize(workDir, sourceScriptDir, modelPath, confidence);
    }

    @PostMapping(value = "/process-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> processImage(@RequestParam("file") MultipartFile file) {
        return processImageInternal(file, "YOLO");
    }

    @PostMapping(value = "/edge-detection", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> processEdgeDetection(@RequestParam("file") MultipartFile file) {
        return processImageInternal(file, "EDGE");
    }

    private ResponseEntity<byte[]> processImageInternal(MultipartFile file, String mode) {
        String requestId = UUID.randomUUID().toString();
        try {
            BufferedImage inputImage = ImageIO.read(file.getInputStream());
            if (inputImage == null)
                return ResponseEntity.badRequest().build();

            BufferedImage bgrImage = new BufferedImage(inputImage.getWidth(), inputImage.getHeight(),
                    BufferedImage.TYPE_3BYTE_BGR);
            bgrImage.getGraphics().drawImage(inputImage, 0, 0, null);

            byte[] pixelData = ((DataBufferByte) bgrImage.getRaster().getDataBuffer()).getData();
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(pixelData.length);
            directBuffer.order(ByteOrder.nativeOrder());
            directBuffer.put(pixelData);
            directBuffer.flip();

            JPyRustBridge bridge = new JPyRustBridge();
            byte[] resultData;

            if ("EDGE".equals(mode)) {
                resultData = bridge.processEdgeDetection(pixelData, bgrImage.getWidth(), bgrImage.getHeight(), 3);
            } else {
                resultData = bridge.processImage(workDir, directBuffer, pixelData.length, bgrImage.getWidth(),
                        bgrImage.getHeight(), 3, requestId);
            }

            if (resultData == null)
                return ResponseEntity.internalServerError().build();
            return ResponseEntity.ok(resultData);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}
