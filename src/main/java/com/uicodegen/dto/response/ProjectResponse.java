package com.uicodegen.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Mirrors Python ProjectResponse — serializes to snake_case so the React frontend
 * can read: response.data.generated_code, response.data.updated_code, etc.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProjectResponse {
    private String id;

    @JsonProperty("user_id")
    private String userId;

    private String title;
    private String framework;

    @JsonProperty("generated_code")
    private String generatedCode;

    @JsonProperty("updated_code")
    private String updatedCode;

    @JsonProperty("chat_messages")
    private List<Map<String, Object>> chatMessages;

    private List<Map<String, Object>> versions;

    @JsonProperty("image_url")
    private String imageUrl;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;
}
