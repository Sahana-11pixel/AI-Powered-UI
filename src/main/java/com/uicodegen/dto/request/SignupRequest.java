package com.uicodegen.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Mirrors Python UserSignup Pydantic model */
@Data
public class SignupRequest {
    @NotBlank @Size(min = 1, max = 100)
    private String name;

    @NotBlank @Email
    private String email;

    @NotBlank @Size(min = 8, max = 128)
    private String password;
}
