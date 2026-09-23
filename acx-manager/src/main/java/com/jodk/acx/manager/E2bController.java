package com.jodk.acx.manager;

import com.jodk.acx.manager.model.SandboxDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** E2B-compatible REST API. */
@RestController
@RequestMapping
public class E2bController {

    private final SandboxManager manager;

    public E2bController(SandboxManager manager) {
        this.manager = manager;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    @PostMapping("/sandboxes")
    public ResponseEntity<SandboxDto> createSandbox(@RequestBody Map<String, Object> body,
                                                    @RequestHeader(value = "X-API-KEY", required = false) String apiKey) {
        String templateID = (String) body.getOrDefault("templateID", body.getOrDefault("template_id", "default"));
        Integer timeout = body.get("timeout") instanceof Number n ? n.intValue() : null;
        @SuppressWarnings("unchecked")
        Map<String, String> metadata = body.get("metadata") instanceof Map<?, ?> m ? (Map<String, String>) m : null;
        SandboxDto dto = manager.claimSandbox(templateID, userOf(apiKey), timeout, metadata);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @GetMapping("/sandboxes")
    public List<SandboxDto> listSandboxes(@RequestHeader(value = "X-API-KEY", required = false) String apiKey) {
        return manager.listSandboxes(userOf(apiKey));
    }

    @GetMapping("/sandboxes/{sandboxID}")
    public SandboxDto describeSandbox(@PathVariable String sandboxID,
                                      @RequestHeader(value = "X-API-KEY", required = false) String apiKey) {
        return manager.getSandbox(userOf(apiKey), sandboxID);
    }

    @DeleteMapping("/sandboxes/{sandboxID}")
    public ResponseEntity<Void> deleteSandbox(@PathVariable String sandboxID,
                                              @RequestHeader(value = "X-API-KEY", required = false) String apiKey) {
        manager.deleteSandbox(userOf(apiKey), sandboxID);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sandboxes/{sandboxID}/pause")
    public ResponseEntity<Void> pauseSandbox(@PathVariable String sandboxID) {
        manager.pauseSandbox(sandboxID);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sandboxes/{sandboxID}/resume")
    public ResponseEntity<Void> resumeSandbox(@PathVariable String sandboxID) {
        manager.resumeSandbox(sandboxID);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sandboxes/{sandboxID}/connect")
    public ResponseEntity<SandboxDto> connectSandbox(@PathVariable String sandboxID,
                                                     @RequestHeader(value = "X-API-KEY", required = false) String apiKey) {
        manager.resumeSandbox(sandboxID);
        return ResponseEntity.ok(manager.getSandbox(userOf(apiKey), sandboxID));
    }

    @PostMapping("/sandboxes/{sandboxID}/timeout")
    public ResponseEntity<Void> setTimeout(@PathVariable String sandboxID, @RequestBody Map<String, Object> body) {
        Integer timeout = body.get("timeout") instanceof Number n ? n.intValue() : 300;
        manager.setTimeout(sandboxID, timeout);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/debug")
    public Map<String, Object> debug() {
        return Map.of("routes", manager.listRoutes());
    }

    private static String userOf(String apiKey) {
        return apiKey != null ? apiKey : "anonymous";
    }
}
