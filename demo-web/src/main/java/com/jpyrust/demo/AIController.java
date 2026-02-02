package com.jpyrust.demo;

import com.jpyrust.JPyRustBridge;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AIController {

    @GetMapping("/chat")
    public Map<String, Object> chat(@RequestParam String message, @RequestParam int id) {
        System.out.println("[Web] Received request: msg=" + message + ", id=" + id);

        try {
            String response = "Text AI is under maintenance. Please check back later.";

            return Map.of(
                    "status", "success",
                    "input_message", message,
                    "input_id", id,
                    "python_response", response);
        } catch (Exception e) {
            e.printStackTrace();
            return Map.of(
                    "status", "error",
                    "message", e.getMessage());
        }
    }
}
