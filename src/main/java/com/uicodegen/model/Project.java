package com.uicodegen.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;
import java.util.Map;

/**
 * MongoDB Project document — mirrors Python ProjectCreate/ProjectResponse models.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "projects")
public class Project {

    @Id
    private String mongoId;

    private String id;               // UUID string
    private String userId;           // Owner user ID
    private String title;
    private String framework;
    private String generatedCode;    // JSON string of files array
    private String updatedCode;
    private List<Map<String, Object>> chatMessages;
    private List<Map<String, Object>> versions;
    private String imageUrl;         // Cloudinary URL
    private String createdAt;
    private String updatedAt;
}
