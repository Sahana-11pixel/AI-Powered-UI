package com.uicodegen.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Mirrors Python GenerateResponse */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class GenerateResponse {
    private String code;
    private String previewHtml;
    private String framework;
    private String message;
}
