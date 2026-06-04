package com.forge.agent.prompt;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/prompts")
public class PromptController {

    private final PromptVersionStore store;

    public PromptController(PromptVersionStore store) { this.store = store; }

    @GetMapping
    public ResponseEntity<List<String>> listActions() {
        return ResponseEntity.ok(store.listActions());
    }

    @GetMapping("/{action}/active")
    public ResponseEntity<?> getActive(@PathVariable String action) {
        return store.getActive(action)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{action}/history")
    public ResponseEntity<List<PromptVersionStore.PromptVersion>> history(@PathVariable String action) {
        return ResponseEntity.ok(store.getHistory(action));
    }

    @PostMapping("/{action}/publish")
    public ResponseEntity<?> publish(@PathVariable String action, @RequestBody PublishRequest req) {
        var pv = store.publish(action, req.systemPrompt, req.userPrompt, req.author, req.changeNote);
        return ResponseEntity.ok(pv);
    }

    @PostMapping("/{action}/rollback/{version}")
    public ResponseEntity<?> rollback(@PathVariable String action, @PathVariable int version) {
        return store.rollback(action, version)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{action}/ab-test")
    public ResponseEntity<?> createAbTest(@PathVariable String action, @RequestBody List<PromptVersionStore.AbVariant> variants) {
        store.createAbTest(action, variants);
        return ResponseEntity.ok(Map.of("status", "ok", "action", action));
    }

    @GetMapping("/{action}/ab-test/stats")
    public ResponseEntity<?> abTestStats(@PathVariable String action) {
        return ResponseEntity.ok(store.getAbTestStats(action));
    }

    @GetMapping("/ab-tests")
    public ResponseEntity<?> listAbTests() {
        return ResponseEntity.ok(store.listActiveAbTests());
    }

    public static class PublishRequest {
        public String systemPrompt;
        public String userPrompt;
        public String author;
        public String changeNote;
    }
}
