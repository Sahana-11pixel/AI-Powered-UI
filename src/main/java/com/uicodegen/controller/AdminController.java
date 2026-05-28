package com.uicodegen.controller;

import com.uicodegen.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Admin-only management endpoints")
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/stats")
    @Operation(summary = "Get dashboard statistics")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(adminService.getStats());
    }

    @GetMapping("/users")
    @Operation(summary = "Paginated list of all users")
    public ResponseEntity<Map<String, Object>> getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(adminService.getUsers(page, limit));
    }

    @GetMapping("/users/{id}")
    @Operation(summary = "Get details for a specific user")
    public ResponseEntity<Map<String, Object>> getUserDetails(@PathVariable String id) {
        return ResponseEntity.ok(adminService.getUserDetails(id));
    }

    @PutMapping("/users/{id}/role")
    @Operation(summary = "Update user role (user/admin)")
    public ResponseEntity<Map<String, String>> updateRole(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        adminService.updateUserRole(id, body.get("role"));
        return ResponseEntity.ok(Map.of("message", "Role updated successfully"));
    }

    @DeleteMapping("/users/{id}")
    @Operation(summary = "Soft-delete a user account")
    public ResponseEntity<Map<String, String>> deleteUser(@PathVariable String id) {
        adminService.softDeleteUser(id);
        return ResponseEntity.ok(Map.of("message", "User deactivated successfully"));
    }

    @GetMapping("/projects")
    @Operation(summary = "Paginated list of all projects")
    public ResponseEntity<Map<String, Object>> getProjects(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(adminService.getProjects(page, limit));
    }

    @DeleteMapping("/projects/{id}")
    @Operation(summary = "Delete any project (admin override)")
    public ResponseEntity<Map<String, String>> deleteProject(@PathVariable String id) {
        adminService.deleteProject(id);
        return ResponseEntity.ok(Map.of("message", "Project deleted successfully"));
    }
}
