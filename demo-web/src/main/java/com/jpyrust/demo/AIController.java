package com.jpyrust.demo;

import com.jpyrust.JPyRustBridge;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AIController {

    private final JPyRustBridge bridge = new JPyRustBridge();

    @GetMapping("/chat")
    public Map<String, Object> chat(@RequestParam String message, @RequestParam int id) {
        // ... legacy code ...
        return Map.of("status", "success", "python_response", "Text AI is under maintenance.");
    }

    @PostMapping("/nlp")
    public Map<String, Object> analyzeSentiment(@RequestBody String text) {
        String result = bridge.processNlp(text);
        return Map.of("result", result);
    }

    @PostMapping("/regression")
    public Map<String, Object> performRegression(@RequestBody String jsonPoints) {
        // jsonPoints example: "[[1, 2], [2, 4], [3, 6]]"
        String result = bridge.processRegression(jsonPoints);
        return Map.of("result", result);
    }
}
