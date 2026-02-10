package com.jpyrust.demo;

import com.jpyrust.JPyRustBridge;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/plugin")
public class PluginController {

    private final JPyRustBridge bridge = new JPyRustBridge();

    @PostMapping("/{taskType}")
    public Map<String, Object> executePlugin(@PathVariable String taskType, @RequestBody Map<String, Object> payload) {
        try {
            String metadata = "NONE";
            if (payload.containsKey("args")) {
                Object args = payload.get("args");
                if (args instanceof Iterable) {
                    StringBuilder sb = new StringBuilder();
                    for (Object o : (Iterable<?>) args) {
                        sb.append(o.toString()).append(" ");
                    }
                    metadata = sb.toString().trim();
                }
            }

            String result = bridge.sendTask(taskType.toUpperCase(), metadata);
            return Map.of("task", taskType, "result", result);

        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }
}
