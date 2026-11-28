package com.jpyrust.demo;

import com.jpyrust.JPyRustBridge;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/plugin")
public class PluginController {

    private final JPyRustBridge bridge;

    public PluginController(JPyRustBridge bridge) {
        this.bridge = bridge;
    }

    @PostMapping("/{taskType}")
    public Map<String, Object> executePlugin(@PathVariable String taskType, @RequestBody Map<String, Object> payload) {
        try {
            String metadata = "";
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

            String result = new String(bridge.call(taskType, new byte[0], metadata), StandardCharsets.UTF_8);
            return Map.of("task", taskType, "result", result);

        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }
}
