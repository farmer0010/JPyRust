package com.jpyrust.demo;

import com.jpyrust.JPyRustBridge;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class StatusController {

    private final JPyRustBridge bridge = new JPyRustBridge();
    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        try {
            String jsonResult = bridge.sendTask("STATUS", "NONE");

            // If result starts with "ERROR", return error map
            if (jsonResult.startsWith("ERROR")) {
                return Map.of("status", "DOWN", "error", jsonResult);
            }

            // Parse JSON string from Python
            try {
                return mapper.readValue(jsonResult, Map.class);
            } catch (Exception e) {
                // If not valid JSON (e.g. raw string), return as raw
                return Map.of("status", "UNKNOWN", "raw", jsonResult);
            }

        } catch (Exception e) {
            return Map.of("status", "DOWN", "error", e.getMessage());
        }
    }
}
