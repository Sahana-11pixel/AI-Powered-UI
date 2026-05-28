package com.uicodegen.service;

import com.uicodegen.dto.request.ProjectCreateRequest;
import com.uicodegen.dto.response.ProjectResponse;
import com.uicodegen.model.Project;
import com.uicodegen.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final MongoTemplate mongoTemplate;
    private final ProjectRepository projectRepository;

    public ProjectResponse createProject(String userId, ProjectCreateRequest req) {
        String now = Instant.now().toString();
        String id   = UUID.randomUUID().toString();

        Project project = Project.builder()
                .id(id)
                .userId(userId)
                .title(req.getTitle())
                .framework(req.getFramework())
                .generatedCode(req.getGeneratedCode())
                .updatedCode(req.getUpdatedCode())
                .chatMessages(req.getChatMessages())
                .versions(req.getVersions())
                .imageUrl(req.getImageUrl())
                .createdAt(now)
                .updatedAt(now)
                .build();

        mongoTemplate.save(project);
        log.info("Project created: {} for user {}", id, userId);
        return toResponse(project);
    }

    public List<ProjectResponse> getUserProjects(String userId) {
        Query q = Query.query(Criteria.where("userId").is(userId));
        return mongoTemplate.find(q, Project.class).stream()
                .map(this::toResponse).toList();
    }

    public ProjectResponse getProject(String id, String userId) {
        Query q = Query.query(Criteria.where("id").is(id).and("userId").is(userId));
        Project project = mongoTemplate.findOne(q, Project.class);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        return toResponse(project);
    }

    public ProjectResponse updateProject(String id, String userId, Map<String, Object> updates) {
        Query q = Query.query(Criteria.where("id").is(id).and("userId").is(userId));
        Project existing = mongoTemplate.findOne(q, Project.class);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }

        Update update = new Update().set("updatedAt", Instant.now().toString());

        // Frontend sends snake_case; support both variants for safety
        if (updates.containsKey("title"))
            update.set("title",       updates.get("title"));
        if (updates.containsKey("updated_code"))
            update.set("updatedCode", updates.get("updated_code"));
        if (updates.containsKey("updatedCode"))
            update.set("updatedCode", updates.get("updatedCode"));
        if (updates.containsKey("chat_messages"))
            update.set("chatMessages", updates.get("chat_messages"));
        if (updates.containsKey("chatMessages"))
            update.set("chatMessages", updates.get("chatMessages"));
        if (updates.containsKey("versions"))
            update.set("versions",    updates.get("versions"));
        if (updates.containsKey("image_url"))
            update.set("imageUrl",    updates.get("image_url"));
        if (updates.containsKey("imageUrl"))
            update.set("imageUrl",    updates.get("imageUrl"));

        mongoTemplate.updateFirst(q, update, Project.class);
        return getProject(id, userId);
    }

    public void deleteProject(String id, String userId) {
        Query q = Query.query(Criteria.where("id").is(id).and("userId").is(userId));
        Project project = mongoTemplate.findOne(q, Project.class);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        mongoTemplate.remove(q, Project.class);
        log.info("Project deleted: {}", id);
    }

    public ProjectResponse toResponse(Project p) {
        return ProjectResponse.builder()
                .id(p.getId())
                .userId(p.getUserId())
                .title(p.getTitle())
                .framework(p.getFramework())
                .generatedCode(p.getGeneratedCode())
                .updatedCode(p.getUpdatedCode())
                .chatMessages(p.getChatMessages())
                .versions(p.getVersions())
                .imageUrl(p.getImageUrl())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
