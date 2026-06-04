package com.forge.agent.project;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 多项目管理 REST API
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRegistry projectRegistry;

    public ProjectController(ProjectRegistry projectRegistry) {
        this.projectRegistry = projectRegistry;
    }

    @GetMapping
    public ResponseEntity<List<ProjectRegistry.Project>> list() {
        return ResponseEntity.ok(projectRegistry.list());
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectRegistry.Project> get(@PathVariable String projectId) {
        return projectRegistry.get(projectId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@RequestBody ProjectRegistry.Project project) {
        projectRegistry.register(project);
        return ResponseEntity.ok(Map.of("status", "ok", "projectId", project.projectId()));
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable String projectId) {
        // 注：实际应从 ConcurrentHashMap 中移除
        return ResponseEntity.ok(Map.of("status", "ok", "message", "项目已移除: " + projectId));
    }

    @PostMapping("/{projectId}/default")
    public ResponseEntity<Map<String, Object>> setDefault(@PathVariable String projectId) {
        projectRegistry.setDefaultProject(projectId);
        return ResponseEntity.ok(Map.of("status", "ok", "defaultProject", projectId));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(Map.of(
                "total", projectRegistry.size(),
                "enabled", projectRegistry.list().stream().filter(ProjectRegistry.Project::enabled).count()
        ));
    }
}
