package com.uicodegen.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Mirrors Python ChatResponse */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ChatResponse {
    private String code;
    
    @com.fasterxml.jackson.annotation.JsonProperty("preview_html")
    private String previewHtml;
    
    private String message;
}
