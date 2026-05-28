package com.uicodegen.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Mirrors Python FirebaseLoginRequest — frontend sends id_token (snake_case) */
@Data
public class FirebaseLoginRequest {
    @NotBlank
    @JsonProperty("id_token")
    private String idToken;
    private String name;
}
