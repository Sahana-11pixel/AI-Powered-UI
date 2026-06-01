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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat endpoint — mirrors the /api/chat endpoint in server.py exactly.
 * Same intent handling, same file-merging, same stub-reset rejection.
 */
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

    // Intents that return original code unchanged — mirrors server.py line 2110
    private static final Set<String> NON_MODIFY_INTENTS = Set.of(
        "CLARIFY", "EXPLAIN", "UNRELATED", "GREETING", "FRAMEWORK_CHANGE"
    );

    @PostMapping("/chat")
    @Operation(summary = "Chat with AI to modify generated code")
    public ChatResponse chat(
            @RequestBody ChatRequest req,
            @AuthenticationPrincipal UserPrincipal user) {

        req.validate();

        // Call GeminiService — which mirrors create_chat_prompt() + safe_gemini_generate()
        String rawResponse = geminiService.chatCode(
            req.getCode(), req.getMessage(), req.getFramework(), req.getChatHistory());

        log.debug("Raw Gemini chat response (first 500 chars): {}",
            rawResponse != null && rawResponse.length() > 500 ? rawResponse.substring(0, 500) : rawResponse);

        // ── Parse Gemini JSON response ──────────────────────────────────────────────
        // Mirrors server.py lines 2074-2101: json.loads() with regex fallback
        Map<?, ?> parsed = null;
        try {
            String cleaned = stripMarkdownFences(rawResponse);
            try {
                parsed = objectMapper.readValue(cleaned, Map.class);
            } catch (Exception parseEx) {
                // Regex fallback — mirrors server.py re.search for "code" field
                String extracted = extractJsonObject(cleaned);
                if (extracted != null) {
                    try {
                        parsed = objectMapper.readValue(extracted, Map.class);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse Gemini chat response: {}", e.getMessage());
        }

        // If completely unparseable, build a fallback result (mirrors server.py fallback block)
        if (parsed == null) {
            parsed = buildFallbackResult(rawResponse, req.getFramework());
        }

        // ── Extract intent, message, code ───────────────────────────────────────────
        // Mirrors server.py lines 2103-2105
        String intent       = parsed.get("intent") instanceof String s ? s.toUpperCase() : "MODIFY";
        String aiMessage    = parsed.get("message") instanceof String m ? m : "Request processed.";
        Object modifiedCode = parsed.get("code");

        log.info("Intent classified: {}", intent);

        // ── Handle non-modify intents ────────────────────────────────────────────────
        // Mirrors server.py lines 2110-2116: CLARIFY, EXPLAIN, UNRELATED, GREETING, FRAMEWORK_CHANGE
        if (NON_MODIFY_INTENTS.contains(intent)) {
            // Return original code unchanged
            List<Map<String, Object>> files = codeSafetyService.parseFilesJson(req.getCode(), req.getFramework());
            String previewHtml = codeSafetyService.buildPreviewHtml(files, req.getFramework());

            logApiUsage(user, req.getFramework());
            return ChatResponse.builder()
                    .code(req.getCode())
                    .previewHtml(previewHtml)
                    .message(aiMessage)
                    .build();
        }

        // ── Handle MODIFY intent ─────────────────────────────────────────────────────
        // Mirrors server.py lines 2117-2188
        if (!"MODIFY".equals(intent) || modifiedCode == null) {
            // AI said MODIFY but returned no code — degrade gracefully
            log.warn("AI returned intent={} but code field is empty/null. Returning original.", intent);
            List<Map<String, Object>> files = codeSafetyService.parseFilesJson(req.getCode(), req.getFramework());
            String previewHtml = codeSafetyService.buildPreviewHtml(files, req.getFramework());
            logApiUsage(user, req.getFramework());
            return ChatResponse.builder()
                    .code(req.getCode())
                    .previewHtml("")
                    .message(aiMessage.isBlank()
                        ? "Your request was understood but the modified code could not be generated. Please try rephrasing."
                        : aiMessage)
                    .build();
        }

        // Parse modified code into files-array JSON string
        // Mirrors server.py lines 2121-2130
        String filesJson;
        try {
            if (modifiedCode instanceof List<?>) {
                // Gemini returned a proper JSON array
                List<?> codeList = (List<?>) modifiedCode;
                boolean validFileArray = codeList.stream().allMatch(
                    f -> f instanceof Map<?, ?> m && m.containsKey("filename") && m.containsKey("content")
                );
                if (validFileArray) {
                    filesJson = objectMapper.writeValueAsString(codeList);
                } else {
                    filesJson = codeSafetyService.parseGeneratedOutput(objectMapper.writeValueAsString(codeList), req.getFramework());
                }
            } else if (modifiedCode instanceof String s) {
                filesJson = codeSafetyService.parseGeneratedOutput(s, req.getFramework());
            } else {
                filesJson = codeSafetyService.parseGeneratedOutput(String.valueOf(modifiedCode), req.getFramework());
            }
        } catch (Exception e) {
            log.warn("Failed to serialize modified code: {}", e.getMessage());
            filesJson = req.getCode();
        }

        // Run code safety pipeline: post-process → validate → repair → detect
        // Mirrors server.py line 2133: run_code_safety_pipeline()
        filesJson = codeSafetyService.runPipeline(filesJson, req.getFramework());

        // ── File-count validation and merging ────────────────────────────────────────
        // Mirrors server.py lines 2136-2188 exactly
        filesJson = mergeFilesIfNeeded(filesJson, req.getCode(), req.getMessage());

        // Check for stub reset rejection — mirrors server.py lines 2169-2183
        String stubCheckResult = checkForStubReset(filesJson, req.getCode(), req.getMessage());
        if (stubCheckResult != null) {
            // AI attempted to reset — reject and return original
            List<Map<String, Object>> origFiles = codeSafetyService.parseFilesJson(req.getCode(), req.getFramework());
            String previewHtml = codeSafetyService.buildPreviewHtml(origFiles, req.getFramework());
            logApiUsage(user, req.getFramework());
            return ChatResponse.builder()
                    .code(req.getCode())
                    .previewHtml(previewHtml)
                    .message(stubCheckResult)
                    .build();
        }

        // Build preview HTML
        List<Map<String, Object>> files = codeSafetyService.parseFilesJson(filesJson, req.getFramework());
        String previewHtml = codeSafetyService.buildPreviewHtml(files, req.getFramework());

        logApiUsage(user, req.getFramework());
        log.info("Chat intent={} for user={}", intent, user.getUserId());

        // Ensure message is never blank
        if (aiMessage == null || aiMessage.isBlank()) {
            aiMessage = "The code has been updated based on your request.";
        }

        return ChatResponse.builder()
                .code(filesJson)
                .previewHtml(previewHtml)
                .message(aiMessage)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Mirrors server.py lines 2136-2186: merge new files into original if fewer returned
    // ─────────────────────────────────────────────────────────────────────────────
    private String mergeFilesIfNeeded(String newFilesJson, String originalCode, String userMessage) {
        try {
            List<?> newFiles = objectMapper.readValue(newFilesJson, List.class);
            List<?> originalFiles;
            try {
                originalFiles = objectMapper.readValue(originalCode, List.class);
            } catch (Exception e) {
                return newFilesJson; // original code is not a JSON array — no merging needed
            }

            if (!(originalFiles instanceof List) || !(newFiles instanceof List)) return newFilesJson;
            if (originalFiles.isEmpty()) return newFilesJson;

            // Only merge if new files are fewer — mirrors server.py line 2147
            if (newFiles.size() >= originalFiles.size()) return newFilesJson;

            // Build map from original files — mirrors server.py line 2149
            Map<String, Map<?, ?>> fileMap = new LinkedHashMap<>();
            for (Object f : originalFiles) {
                if (f instanceof Map<?, ?> fm && fm.containsKey("filename")) {
                    fileMap.put((String) fm.get("filename"), fm);
                }
            }

            // Update map with new (modified) files — mirrors server.py lines 2152-2154
            for (Object nf : newFiles) {
                if (nf instanceof Map<?, ?> nm && nm.containsKey("filename")) {
                    fileMap.put((String) nm.get("filename"), nm);
                }
            }

            // Reconstruct list preserving original order — mirrors server.py lines 2157-2166
            List<Map<?, ?>> mergedFiles = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Object orig : originalFiles) {
                if (orig instanceof Map<?, ?> om && om.containsKey("filename")) {
                    String fname = (String) om.get("filename");
                    mergedFiles.add(fileMap.get(fname));
                    seen.add(fname);
                }
            }
            // Add any entirely new files
            for (Map.Entry<String, Map<?, ?>> entry : fileMap.entrySet()) {
                if (!seen.contains(entry.getKey())) {
                    mergedFiles.add(entry.getValue());
                }
            }

            log.info("Merged {} modified file(s) into original {} file(s).", newFiles.size(), originalFiles.size());

            // Stub check is done AFTER merge — return merged JSON here
            // (stub reset detection is a separate step that uses the merged result)
            return objectMapper.writeValueAsString(mergedFiles);

        } catch (Exception e) {
            log.warn("File count validation/merge failed: {}", e.getMessage());
            return newFilesJson;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Mirrors server.py lines 2169-2183: reject "Welcome" stub resets
    // Returns rejection message if stub detected, null if safe.
    // ─────────────────────────────────────────────────────────────────────────────
    private String checkForStubReset(String filesJson, String originalCode, String userMessage) {
        try {
            List<?> mergedFiles = objectMapper.readValue(filesJson, List.class);
            List<?> newFiles;
            try {
                // We need to check if this was a single-file AI response
                // We detect "Welcome" in the app entry file
                Map<?, ?> appFile = null;
                for (Object f : mergedFiles) {
                    if (f instanceof Map<?, ?> fm) {
                        String fname = String.valueOf(fm.get("filename"));
                        if (fname.equals("App.jsx") || fname.equals("App.tsx") || fname.equals("page.tsx") || fname.equals("app/page.jsx")) {
                            appFile = fm;
                            break;
                        }
                    }
                }

                if (appFile != null) {
                    Object rawContent = appFile.get("content");
                    String content = rawContent != null ? String.valueOf(rawContent) : "";
                    // Check if AI returned a stub with "Welcome" — mirrors server.py line 2170
                    boolean isStub = content.contains("Welcome") && mergedFiles.size() == 1;
                    if (isStub) {
                        // Mirrors server.py lines 2172-2183
                        String messageSuffix;
                        String lowerMsg = userMessage != null ? userMessage.toLowerCase() : "";
                        if (lowerMsg.contains("error") || lowerMsg.contains("bug") || lowerMsg.contains("fix")) {
                            messageSuffix = "If you are trying to fix an error, please explicitly paste the error message from the preview.";
                        } else {
                            messageSuffix = "Could you please be more specific in your request?";
                        }
                        log.warn("AI attempted to reset the project with a stub. Rejecting.");
                        return "I wasn't able to complete your request while preserving the existing project structure. " + messageSuffix;
                    }
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Mirrors server.py lines 2080-2101: regex fallback when json.loads() fails
    // ─────────────────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private Map<?, ?> buildFallbackResult(String rawResponse, String framework) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return Map.of("intent", "MODIFY", "message", "Code updated.", "code", "");
        }

        // Try regex to extract "code" array — mirrors server.py re.search()
        Pattern codePattern = Pattern.compile("\"code\"\\s*:\\s*(\\[\\s*\\{.*?\\}\\s*\\])", Pattern.DOTALL);
        Matcher matcher = codePattern.matcher(rawResponse);
        if (matcher.find()) {
            try {
                Object extracted = objectMapper.readValue(matcher.group(1), List.class);
                return Map.of("intent", "MODIFY", "message", "Code updated.", "code", extracted);
            } catch (Exception ignored) {}
        }

        // Final fallback — mirrors server.py else block
        return Map.of("intent", "MODIFY", "message", "Code updated.", "code", rawResponse);
    }

    private void logApiUsage(UserPrincipal user, String framework) {
        apiUsageRepository.save(ApiUsage.builder()
            .id(UUID.randomUUID().toString())
            .userId(user.getUserId())
            .action("chat")
            .framework(framework)
            .timestamp(Instant.now().toString())
            .tokenUsage(0)
            .build());
    }

    /** Strip ```json or ``` fences from Gemini response */
    private String stripMarkdownFences(String raw) {
        if (raw == null) return "";
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("(?s)^```[a-zA-Z]*\\r?\\n?", "");
            trimmed = trimmed.replaceAll("(?s)\\r?\\n?```\\s*$", "").strip();
        }
        return trimmed;
    }

    /**
     * Extract the first complete JSON object {...} from text that may have prose around it.
     * Handles nested braces correctly.
     */
    private String extractJsonObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        if (start == -1) return null;
        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == stringChar) inString = false;
                continue;
            }
            if (c == '"' || c == '\'') { inString = true; stringChar = c; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }
}
