package com.uicodegen.service;

import com.uicodegen.model.Project;
import com.uicodegen.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final MongoTemplate mongoTemplate;

    // GET /api/admin/stats
    public Map<String, Object> getStats() {
        long totalUsers    = mongoTemplate.count(new Query(), User.class);
        long totalProjects = mongoTemplate.count(new Query(), Project.class);

        // Recent users (last 5)
        Query recentUsersQ = new Query().with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(5);
        List<User> recentUsers = mongoTemplate.find(recentUsersQ, User.class);

        // Recent projects (last 5)
        Query recentProjectsQ = new Query().with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(5);
        List<Project> recentProjects = mongoTemplate.find(recentProjectsQ, Project.class);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_users", totalUsers);
        stats.put("total_projects", totalProjects);
        stats.put("recent_users", recentUsers.stream().map(u -> Map.of(
            "id", u.getId(), "name", u.getName(), "email", u.getEmail(),
            "role", u.getRole(), "created_at", u.getCreatedAt()
        )).toList());
        stats.put("recent_projects", recentProjects.stream().map(p -> Map.of(
            "id", p.getId(), "title", Optional.ofNullable(p.getTitle()).orElse("Untitled"),
            "framework", Optional.ofNullable(p.getFramework()).orElse("unknown"),
            "user_id", p.getUserId(), "created_at", p.getCreatedAt()
        )).toList());
        return stats;
    }

    // GET /api/admin/users?page=0&limit=20
    public Map<String, Object> getUsers(int page, int limit) {
        Query q = new Query().with(Sort.by(Sort.Direction.DESC, "createdAt"))
                             .skip((long) page * limit).limit(limit);
        List<User> users = mongoTemplate.find(q, User.class);
        long total = mongoTemplate.count(new Query(), User.class);

        return Map.of(
            "users", users.stream().map(u -> Map.of(
                "id", u.getId(), "name", u.getName(), "email", u.getEmail(),
                "role", u.getRole(), "created_at", u.getCreatedAt(),
                "is_deleted", u.isDeleted()
            )).toList(),
            "total_count", total,
            "page", page,
            "limit", limit
        );
    }

    // GET /api/admin/users/{id}
    public Map<String, Object> getUserDetails(String userId) {
        Query q = Query.query(Criteria.where("id").is(userId));
        User user = mongoTemplate.findOne(q, User.class);
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");

        long totalProjects  = mongoTemplate.count(
            Query.query(Criteria.where("userId").is(userId)), Project.class);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id",             user.getId());
        details.put("name",           user.getName());
        details.put("email",          user.getEmail());
        details.put("role",           user.getRole());
        details.put("created_at",     user.getCreatedAt());
        details.put("total_projects", totalProjects);
        details.put("is_deleted",     user.isDeleted());
        return details;
    }

    // PUT /api/admin/users/{id}/role
    public void updateUserRole(String userId, String role) {
        Query q = Query.query(Criteria.where("id").is(userId));
        User user = mongoTemplate.findOne(q, User.class);
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        mongoTemplate.updateFirst(q, new Update().set("role", role), User.class);
        log.info("Admin updated role for user {} to {}", userId, role);
    }

    // DELETE /api/admin/users/{id}
    public void softDeleteUser(String userId) {
        Query q = Query.query(Criteria.where("id").is(userId));
        User user = mongoTemplate.findOne(q, User.class);
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        mongoTemplate.updateFirst(q,
            new Update().set("isDeleted", true).set("deletedAt", Instant.now().toString()),
            User.class);
        log.info("Admin soft-deleted user: {}", userId);
    }

    // GET /api/admin/projects?page=0&limit=20
    public Map<String, Object> getProjects(int page, int limit) {
        Query q = new Query().with(Sort.by(Sort.Direction.DESC, "createdAt"))
                             .skip((long) page * limit).limit(limit);
        List<Project> projects = mongoTemplate.find(q, Project.class);
        long total = mongoTemplate.count(new Query(), Project.class);

        return Map.of(
            "projects", projects.stream().map(p -> Map.of(
                "id",         p.getId(),
                "user_id",    p.getUserId(),
                "title",      Optional.ofNullable(p.getTitle()).orElse("Untitled"),
                "framework",  Optional.ofNullable(p.getFramework()).orElse("unknown"),
                "created_at", p.getCreatedAt()
            )).toList(),
            "total_count", total,
            "page", page,
            "limit", limit
        );
    }

    // DELETE /api/admin/projects/{id}
    public void deleteProject(String projectId) {
        Query q = Query.query(Criteria.where("id").is(projectId));
        Project p = mongoTemplate.findOne(q, Project.class);
        if (p == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        mongoTemplate.remove(q, Project.class);
        log.info("Admin deleted project: {}", projectId);
    }
}
