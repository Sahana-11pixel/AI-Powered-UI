package com.uicodegen.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** Mirrors Python ChatRequest */
@Data
public class ChatRequest {
    @NotBlank
    private String code;

    @NotBlank @Size(max = 4000)
    private String message;

    @NotBlank
    private String framework;

    @JsonProperty("project_id")
    private String projectId;
    
    @JsonProperty("chat_history")
    private List<Map<String, Object>> chatHistory;

    public void validate() {
        if (code == null || code.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "code is required");
        }
        if (message == null || message.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "message is required");
        }
        if (framework == null || framework.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "framework is required");
        }
    }
}
