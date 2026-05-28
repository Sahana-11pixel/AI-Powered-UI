package com.uicodegen.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Tracks every upload/generate/chat API call per user — mirrors Python api_usage collection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "api_usage")
public class ApiUsage {

    @Id
    private String mongoId;

    private String id;
    private String userId;
    private String action;       // "upload" | "generate" | "chat"
    private String framework;
    private String timestamp;
    private int tokenUsage;
}
