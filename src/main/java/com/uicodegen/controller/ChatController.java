package com.uicodegen.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uicodegen.dto.request.ChatRequest;
import com.uicodegen.dto.response.ChatResponse;
import com.uicodegen.model.ApiUsage;
import com.uicodegen.repository.ApiUsageRepository;
import com.uicodegen.security.UserPrincipal;
import com.uicodegen.service.CodeSafetyService;
import com.uicodegen.service.GeminiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Chat", description = "AI chat for code refinement")
public class ChatController {

    private final GeminiService geminiService;
    private final CodeSafetyService codeSafetyService;
    private final ApiUsageRepository apiUsageRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/chat")
    @Operation(summary = "Chat with AI to modify generated code")
    public ChatResponse chat(
            @RequestBody ChatRequest req,
            @AuthenticationPrincipal UserPrincipal user) {

        req.validate();

        // Call Gemini chat
        String rawResponse = geminiService.chatCode(
            req.getCode(), req.getMessage(), req.getFramework(), req.getChatHistory());

        // Parse the JSON response from Gemini: {"intent": "...", "code": ..., "message": "..."}
        String updatedCode = req.getCode(); // default: return original unchanged
        String aiMessage   = "Understood.";
        String intent      = "EXPLAIN";

        try {
            Map<?, ?> parsed = objectMapper.readValue(rawResponse, Map.class);
            if (parsed.get("intent") instanceof String i) intent = i.toUpperCase();
            if (parsed.get("message") instanceof String m) aiMessage = m;

            // Only update code when AI explicitly wants to MODIFY
            if ("MODIFY".equals(intent)) {
                Object codeField = parsed.get("code");
                if (codeField instanceof String s && !s.isBlank()) {
                    updatedCode = s;
                } else if (codeField instanceof List<?>) {
                    updatedCode = objectMapper.writeValueAsString(codeField);
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse Gemini chat JSON response, treating as raw code: {}", e.getMessage());
            // Only fall back to raw response if it looks like code, not an error message
            if (rawResponse != null && rawResponse.trim().startsWith("[")) {
                updatedCode = rawResponse;
                intent = "MODIFY";
            }
        }

        String cleanedJson;
        if ("MODIFY".equals(intent)) {
            // Run safety pipeline only on modified code
            cleanedJson = codeSafetyService.runPipeline(updatedCode, req.getFramework());
        } else {
            // EXPLAIN / UNRELATED — return original code as-is, no pipeline needed
            cleanedJson = req.getCode();
        }

        // Build preview HTML
        List<Map<String, Object>> files = codeSafetyService.parseFilesJson(cleanedJson, req.getFramework());
        String previewHtml = codeSafetyService.buildPreviewHtml(files, req.getFramework());

        // Log API usage
        apiUsageRepository.save(ApiUsage.builder()
            .id(UUID.randomUUID().toString())
            .userId(user.getUserId())
            .action("chat")
            .framework(req.getFramework())
            .timestamp(Instant.now().toString())
            .tokenUsage(0)
            .build());

        log.info("Chat intent={} for user {}", intent, user.getUserId());

        return ChatResponse.builder()
                .code(cleanedJson)
                .previewHtml(previewHtml)
                .message(aiMessage)
                .build();
    }
}
