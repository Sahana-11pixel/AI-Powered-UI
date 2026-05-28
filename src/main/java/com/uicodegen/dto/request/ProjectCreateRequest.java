package com.uicodegen.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** Mirrors Python ProjectCreate — frontend sends snake_case field names */
@Data
public class ProjectCreateRequest {
    @NotBlank
    private String title;

    @NotBlank
    private String framework;

    // Frontend sends "generated_code"
    @JsonProperty("generated_code")
    private String generatedCode;

    // Frontend sends "updated_code"
    @JsonProperty("updated_code")
    private String updatedCode;

    // Frontend sends "chat_messages"
    @JsonProperty("chat_messages")
    private List<Map<String, Object>> chatMessages;

    private List<Map<String, Object>> versions;

    // Frontend sends "image_url"
    @JsonProperty("image_url")
    private String imageUrl;
}
