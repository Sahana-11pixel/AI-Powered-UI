package com.uicodegen.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Calls Gemini REST API via OkHttp — mirrors Python safe_gemini_generate().
 * Uses the generateContent endpoint directly (no heavy SDK dependency).
 */
@Slf4j
@Service
public class GeminiService {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private static final Set<String> ALLOWED_FRAMEWORKS = Set.of(
        "html_css", "react", "next_js", "nuxt_js", "svelte", "vue", "tailwind", "bootstrap", "vanilla_js"
    );

    public GeminiService(
            @Value("${gemini.api-key}") String apiKey,
            @Value("${gemini.base-url}") String baseUrl,
            @Value("${gemini.model}") String model
    ) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Generate UI code from a screenshot image.
     * Mirrors Python's generate_code endpoint logic.
     */
    public String generateCode(byte[] imageBytes, String framework) {
        validateFramework(framework);

        String prompt = buildGenerationPrompt(framework);
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        // Build Gemini request body with image + text
        Map<String, Object> imagePart = Map.of(
            "inline_data", Map.of(
                "mime_type", "image/png",
                "data", base64Image
            )
        );
        Map<String, Object> textPart = Map.of("text", prompt);

        Map<String, Object> content = Map.of(
            "parts", List.of(imagePart, textPart)
        );

        Map<String, Object> requestBody = Map.of(
            "contents", List.of(content),
            "generationConfig", Map.of("maxOutputTokens", 8192)
        );

        return callGemini(requestBody);
    }

    /**
     * Chat to refine/modify existing code.
     * Mirrors Python's create_chat_prompt() + generate logic.
     */
    public String chatCode(String code, String message, String framework,
                           List<Map<String, Object>> chatHistory) {
        String prompt = buildChatPrompt(code, message, framework, chatHistory);

        Map<String, Object> textPart = Map.of("text", prompt);
        Map<String, Object> content = Map.of("parts", List.of(textPart));
        Map<String, Object> requestBody = Map.of(
            "contents", List.of(content),
            "generationConfig", Map.of(
                "maxOutputTokens", 8192,
                "responseMimeType", "application/json"
            )
        );

        return callGemini(requestBody);
    }

    /**
     * AI repair pass — send broken code + errors back to Gemini to fix.
     * Mirrors Python repair_code_with_ai().
     */
    public String repairCode(String filesJson, String framework, List<String> errors) {
        String errorList = String.join("\n", errors.stream().map(e -> "- " + e).toList());
        String repairPrompt = String.format("""
            You are a code repair assistant. Fix ALL of the following errors in the %s code below.
            
            ERRORS FOUND:
            %s
            
            CURRENT CODE:
            %s
            
            RULES:
            1. Fix every listed error.
            2. Return a JSON array of file objects: [{"filename": "...", "content": "..."}]
            3. Return ONLY the raw JSON array. No markdown, no explanation.
            4. Every file must contain COMPLETE code.
            """, framework, errorList, filesJson);

        Map<String, Object> textPart = Map.of("text", repairPrompt);
        Map<String, Object> content = Map.of("parts", List.of(textPart));
        Map<String, Object> requestBody = Map.of(
            "contents", List.of(content),
            "generationConfig", Map.of(
                "maxOutputTokens", 8192,
                "responseMimeType", "application/json"
            )
        );

        return callGemini(requestBody);
    }

    // ─── Internal HTTP call ─────────────────────────────────────────────────────

    private String callGemini(Map<String, Object> requestBody) {
        try {
            String url = baseUrl + "/" + model + ":generateContent?key=" + apiKey;
            // Log URL without the key for debugging
            log.info("Calling Gemini: {}/{}:generateContent", baseUrl, model);
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    // Log the full Gemini error response so it appears in your terminal
                    log.error("Gemini HTTP {} error. URL: {}/{}:generateContent | Response: {}",
                        response.code(), baseUrl, model, responseBody);
                    if (response.code() == 429) {
                        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                            "The AI model is currently busy. Please wait a moment and try again.");
                    } else if (response.code() == 503 || response.code() == 504) {
                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "The AI service is temporarily overloaded. Please try again.");
                    } else if (response.code() == 400) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "The request was too large or complex for the AI. Try a simpler image.");
                    } else if (response.code() == 403) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "Gemini API key is invalid or does not have access to model: " + model);
                    } else if (response.code() == 404) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Gemini model not found: " + model + ". Check application.yml gemini.model setting.");
                    }
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "AI processing encountered an unexpected issue. Please try again.");
                }

                // Parse Gemini response → extract text
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode candidates = root.path("candidates");
                if (candidates.isArray() && candidates.size() > 0) {
                    JsonNode parts = candidates.get(0).path("content").path("parts");
                    if (parts.isArray() && parts.size() > 0) {
                        return parts.get(0).path("text").asText();
                    }
                }

                log.error("Unexpected Gemini response structure: {}", responseBody);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "AI returned an unexpected response format.");
            }

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            // Log full stack trace so error is visible in terminal
            log.error("Gemini API call failed with exception: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "AI processing encountered an unexpected issue: " + e.getMessage());
        }
    }

    // ─── Prompt builders ────────────────────────────────────────────────────────

    private void validateFramework(String framework) {
        if (!ALLOWED_FRAMEWORKS.contains(framework)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid framework. Allowed: " + ALLOWED_FRAMEWORKS);
        }
    }

    private String buildGenerationPrompt(String framework) {
        String[] colorPalettes = {
            "#6366f1,#4f46e5,#818cf8,#0f172a,#f8fafc",
            "#ec4899,#db2777,#f472b6,#18181b,#fafafa",
            "#06b6d4,#0891b2,#22d3ee,#020617,#f1f5f9",
            "#10b981,#059669,#34d399,#050505,#ffffff"
        };
        String[] palette = colorPalettes[new Random().nextInt(colorPalettes.length)].split(",");

        String multiFileOutput = """
            OUTPUT FORMAT: Return a JSON array of files. Each object has "filename" and "content" keys.
            The FIRST file must be the main entry point.
            Example: [{"filename": "App.jsx", "content": "..."}, {"filename": "Header.jsx", "content": "..."}]
            Return ONLY the raw JSON array. No markdown, no explanation.
            """;

        String singleFileOutput = """
            OUTPUT FORMAT: Return ONLY the raw code. No markdown, no explanations, no wrapping.
            First character = code start, last character = code end.
            """;

        boolean isMultiFile = Set.of("react", "vue", "svelte", "next_js", "nuxt_js").contains(framework);
        String outputFormat = isMultiFile ? multiFileOutput : singleFileOutput;

        return String.format("""
            You are a UI to code generator. Analyze the screenshot and generate SIMILAR (not identical) code.
            
            Design Requirements:
            1. Preserve layout structure and component hierarchy.
            2. Modify at least 30%% of visual styling (spacing, typography, border radius).
            3. Do NOT reuse exact text from the original UI — rewrite all headings and labels.
            4. Use only relative layouts (Flexbox/Grid). Avoid absolute positioning.
            5. If the UI is complex, simplify — max 3-4 files. Prioritize clean, runnable code.
            6. Keep output fully responsive, syntactically valid, and always runnable.
            
            Framework: %s
            
            Color palette to use:
            Primary: %s | Secondary: %s | Accent: %s | Background: %s | Text: %s
            
            %s
            """, framework, palette[0], palette[1], palette[2], palette[3], palette[4], outputFormat);
    }

    private String buildChatPrompt(String code, String message, String framework,
                                   List<Map<String, Object>> chatHistory) {
        boolean isMultiFile = Set.of("react", "vue", "svelte", "next_js", "nuxt_js").contains(framework);

        String codeFormat = isMultiFile
            ? "\"code\" MUST be a JSON array: [{\"filename\": \"...\", \"content\": \"...\"}]. Include ALL files."
            : "\"code\" MUST be the COMPLETE source code as a single string.";

        String historyText = "";
        if (chatHistory != null && !chatHistory.isEmpty()) {
            List<Map<String, Object>> recent = chatHistory.subList(
                Math.max(0, chatHistory.size() - 5), chatHistory.size());
            StringBuilder sb = new StringBuilder("\nPrevious conversation:\n");
            for (Map<String, Object> msg : recent) {
                sb.append(msg.get("role")).append(": ").append(msg.get("content")).append("\n");
            }
            historyText = sb.toString();
        }

        return String.format("""
            You are an expert UI developer. Modify the following %s code based on the user's request.
            %s
            
            CURRENT CODE:
            %s
            
            USER REQUEST: %s
            
            RESPONSE FORMAT — return ONLY valid JSON (no markdown):
            {
              "intent": "MODIFY" or "EXPLAIN",
              "code": <updated code — %s>,
              "message": "<brief explanation of what changed>"
            }
            
            Rules:
            - Return COMPLETE code for every file, never partial.
            - If user only asks a question, set intent=EXPLAIN and return the original code unchanged.
            - Never truncate code.
            """, framework, historyText, code, message, codeFormat);
    }
}
