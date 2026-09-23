package com.jodk.acx.manager;

import com.jodk.acx.manager.model.PoolView;
import com.jodk.acx.manager.model.SandboxView;
import com.jodk.acx.manager.mount.DynamicMountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Management-plane REST API consumed by the static frontend: warm-pool (SandboxSet) CRUD,
 * starting an application pod from a pool, dynamic mounts and in-place image adjustment.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final AdminService admin;
    private final SandboxManager manager;
    private final DynamicMountService mounts;

    public ApiController(AdminService admin, SandboxManager manager, DynamicMountService mounts) {
        this.admin = admin;
        this.manager = manager;
        this.mounts = mounts;
    }

    // ----- warm pools (SandboxSet) -----

    @GetMapping("/pools")
    public List<PoolView> pools() {
        return admin.pools();
    }

    @PostMapping("/pools")
    public ResponseEntity<PoolView> createPool(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.createPool(body));
    }

    @PostMapping("/pools/{pool}/scale")
    public ResponseEntity<PoolView> scalePool(@PathVariable String pool, @RequestBody Map<String, Object> body) {
        int replicas = body.get("replicas") instanceof Number n ? n.intValue()
                : Integer.parseInt(String.valueOf(body.getOrDefault("replicas", "1")));
        admin.scalePool(pool, replicas);
        return ResponseEntity.ok(admin.pool(pool));
    }

    @DeleteMapping("/pools/{pool}")
    public ResponseEntity<Void> deletePool(@PathVariable String pool) {
        admin.deletePool(pool);
        return ResponseEntity.noContent().build();
    }

    /** CSI PVs usable for dynamic mounts (datalist candidates for the UI). */
    @GetMapping("/pvs")
    public List<Map<String, Object>> pvs() {
        return admin.csiPvs();
    }

    // ----- sandboxes -----

    @GetMapping("/sandboxes")
    public List<SandboxView> sandboxes() {
        return admin.sandboxes();
    }

    @GetMapping("/sandboxes/{sandboxId}")
    public SandboxView sandbox(@PathVariable String sandboxId) {
        return admin.sandbox(sandboxId);
    }

    /** Start an application pod from a warm pool, with optional image / mounts / env overrides. */
    @PostMapping("/pools/{pool}/start")
    public ResponseEntity<SandboxView> startFromPool(@PathVariable String pool,
                                                     @RequestBody(required = false) Map<String, Object> body,
                                                     @RequestHeader(value = "X-API-KEY", required = false) String apiKey) {
        Map<String, Object> req = body != null ? body : Map.of();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(admin.startFromPool(pool, req, userOf(apiKey)));
    }

    /** Attach custom mounts to a running sandbox; the operator rebuilds the pod for the change. */
    @PostMapping("/sandboxes/{sandboxId}/mounts")
    public ResponseEntity<SandboxView> addMounts(@PathVariable String sandboxId,
                                                 @RequestBody Map<String, Object> body,
                                                 @RequestHeader(value = "X-API-KEY", required = false) String apiKey) {
        return ResponseEntity.accepted().body(admin.addMounts(sandboxId, body, userOf(apiKey)));
    }

    /** Attach a CSI-backed volume to a running sandbox without recreating its pod. */
    @PostMapping("/sandboxes/{sandboxId}/dynamic-mounts")
    public ResponseEntity<SandboxView> dynamicMount(@PathVariable String sandboxId,
                                                    @RequestBody Map<String, Object> body,
                                                    @RequestHeader(value = "X-API-KEY", required = false) String apiKey) {
        return ResponseEntity.accepted().body(mounts.dynamicMount(sandboxId, body));
    }

    /** Detach a previously attached dynamic mount; the pod keeps running. */
    @PostMapping("/sandboxes/{sandboxId}/dynamic-umount")
    public ResponseEntity<SandboxView> dynamicUmount(@PathVariable String sandboxId,
                                                     @RequestBody Map<String, Object> body,
                                                     @RequestHeader(value = "X-API-KEY", required = false) String apiKey) {
        String mountId = body.get("mountId") != null ? String.valueOf(body.get("mountId")) : null;
        return ResponseEntity.accepted().body(mounts.dynamicUmount(sandboxId, mountId));
    }

    /** In-place image swap of a running sandbox pod (name / ip preserved). */
    @PostMapping("/sandboxes/{sandboxId}/inplace")
    public ResponseEntity<SandboxView> inplace(@PathVariable String sandboxId,
                                               @RequestBody Map<String, Object> body,
                                               @RequestHeader(value = "X-API-KEY", required = false) String apiKey) {
        String image = body.get("image") != null ? String.valueOf(body.get("image")) : null;
        return ResponseEntity.accepted().body(admin.inplaceImage(sandboxId, image, userOf(apiKey)));
    }

    @DeleteMapping("/sandboxes/{sandboxId}")
    public ResponseEntity<Void> deleteSandbox(@PathVariable String sandboxId,
                                              @RequestHeader(value = "X-API-KEY", required = false) String apiKey) {
        manager.deleteSandbox(userOf(apiKey), sandboxId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sandboxes/{sandboxId}/pause")
    public ResponseEntity<Void> pause(@PathVariable String sandboxId) {
        manager.pauseSandbox(sandboxId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sandboxes/{sandboxId}/resume")
    public ResponseEntity<Void> resume(@PathVariable String sandboxId) {
        manager.resumeSandbox(sandboxId);
        return ResponseEntity.noContent().build();
    }

    // ----- error mapping -----

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "bad request" : e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage() == null ? "conflict" : e.getMessage()));
    }

    private static String userOf(String apiKey) {
        return apiKey != null ? apiKey : "anonymous";
    }
}
