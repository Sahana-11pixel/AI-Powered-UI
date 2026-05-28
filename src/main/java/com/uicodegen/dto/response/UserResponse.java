package com.uicodegen.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Mirrors Python UserResponse */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserResponse {
    private String id;
    private String name;
    private String email;
    private String role;
    private String createdAt;
    @Builder.Default
    private boolean isDeleted = false;
}
