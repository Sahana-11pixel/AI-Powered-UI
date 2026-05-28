package com.uicodegen.controller;

import com.uicodegen.dto.request.ProjectCreateRequest;
import com.uicodegen.dto.response.ProjectResponse;
import com.uicodegen.security.UserPrincipal;
import com.uicodegen.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Tag(name = "Projects", description = "Project CRUD endpoints")
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping
    @Operation(summary = "Save a new project")
    public ResponseEntity<ProjectResponse> createProject(
            @Valid @RequestBody ProjectCreateRequest req,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(projectService.createProject(user.getUserId(), req));
    }

    @GetMapping
    @Operation(summary = "List all projects for current user")
    public ResponseEntity<List<ProjectResponse>> getProjects(
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(projectService.getUserProjects(user.getUserId()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single project by ID")
    public ResponseEntity<ProjectResponse> getProject(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(projectService.getProject(id, user.getUserId()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a project")
    public ResponseEntity<ProjectResponse> updateProject(
            @PathVariable String id,
            @RequestBody Map<String, Object> updates,
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(projectService.updateProject(id, user.getUserId(), updates));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a project")
    public ResponseEntity<Map<String, String>> deleteProject(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal user) {
        projectService.deleteProject(id, user.getUserId());
        return ResponseEntity.ok(Map.of("message", "Project deleted successfully"));
    }
}
