package com.jpyrust.demo;

import com.jpyrust.JPyRustBridge;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST Controller for Text-based AI tasks (Sentiment Analysis).
 */
@RestController
@RequestMapping("/api/ai")
public class AITextController {

    /**
     * Analyze sentiment of input text.
     * 
     * POST /api/ai/text
     * Body: { "text": "This is a great product!" }
     * Response: { "sentiment": "POSITIVE (confidence: 0.60)", "input_length": 25 }
     */
    @PostMapping(value = "/text", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> analyzeText(@RequestBody Map<String, String> request) {
        String text = request.get("text");

        if (text == null || text.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Missing 'text' field in request body"));
        }

        System.out.println("[AITextController] Request: " + text.substring(0, Math.min(50, text.length())) + "...");

        long startTime = System.nanoTime();

        try {
            JPyRustBridge bridge = new JPyRustBridge();
            String result = bridge.processText(text);

            long elapsed = (System.nanoTime() - startTime) / 1_000_000;

            System.out.println("[AITextController] Result: " + result + " | " + elapsed + "ms");

            return ResponseEntity.ok(Map.of(
                    "sentiment", result,
                    "input_length", text.length(),
                    "processing_time_ms", elapsed));

        } catch (Exception e) {
            System.err.println("[AITextController] Error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage()));
        }
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "JPyRust Universal Bridge",
                "tasks", "YOLO, SENTIMENT"));
    }
}
