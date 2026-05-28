package com.uicodegen.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Set;

/** Mirrors Python GenerateRequest — frontend sends snake_case field names */
@Data
public class GenerateRequest {

    private static final Set<String> ALLOWED_FRAMEWORKS = Set.of(
        "html_css", "react", "next_js", "nuxt_js", "svelte", "vue", "tailwind", "bootstrap", "vanilla_js"
    );

    // Frontend sends "image_id"
    @JsonProperty("image_id")
    private String imageId;

    // Framework is single word — maps fine
    private String framework;

    // Frontend sends "image_url"
    @JsonProperty("image_url")
    private String imageUrl;

    public void validate() {
        if (imageId == null || imageId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "image_id is required");
        }
        if (framework == null || !ALLOWED_FRAMEWORKS.contains(framework)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid framework. Allowed: " + ALLOWED_FRAMEWORKS);
        }
    }
}
