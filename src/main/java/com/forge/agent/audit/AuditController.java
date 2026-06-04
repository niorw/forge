package com.forge.agent.audit;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;
    public AuditController(AuditService auditService) { this.auditService = auditService; }

    @GetMapping
    public ResponseEntity<List<AuditLog>> query(
            @RequestParam(required = false) AuditLog.AuditAction action,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String threadId,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(auditService.query(action, actor, projectId, threadId, limit));
    }

    @GetMapping("/recent")
    public ResponseEntity<List<AuditLog>> recent(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(auditService.recent(limit));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(auditService.stats());
    }
}
