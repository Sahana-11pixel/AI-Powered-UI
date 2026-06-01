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
 * Calls Gemini REST API via OkHttp.
 * Logic is 100% identical to server.py — only the language differs.
 *
 * Mirrors:
 *   - create_similarity_prompt()  → buildGenerationPrompt()
 *   - create_chat_prompt()        → buildChatPrompt()
 *   - safe_gemini_generate()      → callGeminiWithRetry()
 */
@Slf4j
@Service
public class GeminiService {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private static final int MAX_RETRIES = 3;

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
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PUBLIC API — mirrors the endpoint logic in server.py
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Generate UI code from a screenshot image.
     * Mirrors: safe_gemini_generate() called from /generate endpoint in server.py
     */
    public String generateCode(byte[] imageBytes, String framework) {
        validateFramework(framework);

        String prompt = buildGenerationPrompt(framework);
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        // Python sends: contents={"parts": [{"text": prompt}, {"inline_data": ...}]}
        Map<String, Object> textPart = Map.of("text", prompt);
        Map<String, Object> imagePart = Map.of(
            "inline_data", Map.of(
                "mime_type", "image/png",
                "data", base64Image
            )
        );

        Map<String, Object> content = Map.of(
            "parts", List.of(textPart, imagePart)
        );

        Map<String, Object> requestBody = Map.of(
            "contents", List.of(content),
            "generationConfig", Map.of(
                "maxOutputTokens", 8192,
                "temperature", 0.4
            )
        );

        return callGeminiWithRetry(requestBody);
    }

    /**
     * Chat to modify/explain existing code.
     * Mirrors: create_chat_prompt() + safe_gemini_generate() with response_mime_type=application/json
     * The Python version sends the entire prompt as ONE single text block.
     */
    public String chatCode(String code, String message, String framework,
                           List<Map<String, Object>> chatHistory) {

        // Python calls create_chat_prompt() and sends it as a single text part
        String prompt = buildChatPrompt(code, message, framework, chatHistory);

        Map<String, Object> textPart = Map.of("text", prompt);
        Map<String, Object> content = Map.of("parts", List.of(textPart));

        // Python passes config={"response_mime_type": "application/json"}
        Map<String, Object> requestBody = Map.of(
            "contents", List.of(content),
            "generationConfig", Map.of(
                "maxOutputTokens", 8192,
                "temperature", 0.4,
                "responseMimeType", "application/json"
            )
        );

        return callGeminiWithRetry(requestBody);
    }

    /**
     * AI repair pass — send broken code + errors back to Gemini to fix.
     * Mirrors: repair_code_with_ai() in server.py
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

        return callGeminiWithRetry(requestBody);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // safe_gemini_generate() equivalent — retry with friendly error mapping
    // ─────────────────────────────────────────────────────────────────────────────

    private String callGeminiWithRetry(Map<String, Object> requestBody) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return callGemini(requestBody);
            } catch (ResponseStatusException e) {
                int code = e.getStatusCode().value();
                boolean retryable = (code == 429 || code == 503 || code == 504);
                if (retryable && attempt < MAX_RETRIES) {
                    long waitMs = (long) Math.pow(2, attempt) * 1000L; // 2s, 4s, 8s
                    log.warn("Gemini returned {} on attempt {}. Retrying in {}ms...", code, attempt, waitMs);
                    try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                throw e;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    long waitMs = (long) Math.pow(2, attempt) * 1000L;
                    log.warn("Gemini call failed on attempt {} ({}). Retrying in {}ms...", attempt, e.getMessage(), waitMs);
                    try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                log.error("Gemini API call failed after {} attempts: {}", MAX_RETRIES, e.getMessage(), e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "AI processing encountered an unexpected issue. Please try again.");
            }
        }
    }

    private String callGemini(Map<String, Object> requestBody) throws Exception {
        String url = baseUrl + "/" + model + ":generateContent?key=" + apiKey;
        log.info("Calling Gemini: {}/{}:generateContent", baseUrl, model);
        String jsonBody = objectMapper.writeValueAsString(requestBody);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("Gemini HTTP {} — model={} — body={}", response.code(), model, responseBody);
                String errorMsg = responseBody;
                // Mirror server.py safe_gemini_generate() error mapping exactly
                if (response.code() == 429 || errorMsg.contains("RESOURCE_EXHAUSTED") || errorMsg.toLowerCase().contains("quota")) {
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "The AI model is currently busy or has reached its limit. Please wait a moment and try again.");
                } else if (response.code() == 503 || response.code() == 504 || errorMsg.contains("UNAVAILABLE")) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The AI service is temporarily overloaded. Please try again in a few seconds.");
                } else if (response.code() == 400 || errorMsg.contains("INVALID_ARGUMENT")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The request was too large or complex for the AI. Try a simpler image or a shorter message.");
                }
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "AI processing encountered an unexpected issue. Please try again.");
            }

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
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // create_similarity_prompt() — 100% identical logic to server.py
    // ─────────────────────────────────────────────────────────────────────────────

    private String buildGenerationPrompt(String framework) {
        Map<String, String> colors = generateRandomColors();

        String basePrompt = String.format("""
            You are a UI to code generator. Analyze the screenshot and generate SIMILAR (not identical) code.
            
            Design Requirements:
            1. Preserve layout structure and component hierarchy (header, sections, footer, etc.).
            2. Maintain similar functionality and user flow.
                - You MUST modify at least 30%% of visual styling choices (spacing, typography scale, border radius, alignment, section ordering where reasonable).
                - Do NOT replicate identical spacing, font sizes, or exact layout proportions.
                - Slightly restructure sections where possible (e.g., convert stacked layout to grid, change alignment left ↔ center when appropriate).
            3. - You MUST NOT reuse any exact phrases from the original UI.
               - Rewrite all headings, paragraphs, and button labels with new wording.
               - If the screenshot contains brand names, replace them with fictional generic brands.
               - Never copy text verbatim from the image.
            4. Use only relative layouts (Flexbox/Grid). Avoid absolute positioning.
            5. Slightly vary spacing and typography while maintaining visual balance.
            6. If the UI is complex (dashboard, multi-column, image-heavy, etc.), SIMPLIFY:
               - Use a maximum of 3-4 sub-component files. Combine smaller sections rather than creating many files.
               - Approximate complex charts/graphs with simple placeholder divs showing sample data.
               - Prioritize clean, ERROR-FREE, runnable code over pixel-perfect accuracy.
               - NEVER generate truncated or incomplete code. If running out of space, simplify the design.
            7. The result should feel like a redesign of the same product by a different company.
            8. Keep the output clean, fully responsive, syntactically valid, and ALWAYS runnable without errors.
            9. NEVER import libraries not explicitly listed as supported in the framework rules.
            
            UI Validation Rule
            - Reject the image only if it is completely blank, corrupted, or contains no identifiable UI elements.
            - Do not reject due to blur, low resolution, glassmorphism effects, minimal design, or complex backgrounds.
            - If any functional UI components are visible (buttons, text, inputs, cards, containers, etc.), generate the code.
            - Only respond with "No UI detected in the image." when absolutely no UI structure is present.
            
            Framework: %s
            """, framework);

        String multiFileOutput = """
            
            OUTPUT FORMAT: Return a JSON array of files. Each object has "filename" and "content" keys.
            The FIRST file must be the main entry point. Break complex UIs into logical components.
            Example: [{"filename": "App.jsx", "content": "import React..."}, {"filename": "Header.jsx", "content": "..."}]
            Return ONLY the raw JSON array. No markdown, no explanation.
            """;

        String singleFileOutput = """
            
            OUTPUT FORMAT: Return ONLY the raw code. No markdown, no explanations, no wrapping.
            First character = code start, last character = code end.
            """;

        String frameworkRules = switch (framework) {
            case "html_css" -> String.format("""
                Generate a complete <!DOCTYPE html> page with all CSS in a <style> tag.
                Semantic HTML5, Flexbox/Grid layout, responsive media queries, functional form elements.
                If the screenshot includes images, implement them using:
                - Standard <img> tags with https://picsum.photos/WIDTH/HEIGHT (no /id/ paths) as placeholders.
                - Explicit width and height attributes
                - No CSS background-image
                COLOR RULE:
                Use this new color palette (do NOT reuse original colors):
                - Primary: %s
                - Secondary: %s
                - Accent: %s
                - Background: %s
                - Text: %s
                """, colors.get("primary"), colors.get("secondary"), colors.get("accent"), colors.get("bg"), colors.get("text")) + singleFileOutput;

            case "react" -> """
                Generate React functional components.
                Split into multiple files: main App.jsx + separate component files (max 3-4 component files).
                Each component file should import React and export default.
                App.jsx should import and compose all sub-components.
                
                STRICT RULES:
                1. ONLY use Tailwind CSS utility classes. Do not use inline styles unless absolutely necessary.
                2. ALWAYS use `className=` instead of `class=`.
                3. ONLY use standard DOM elements (div, span, button, etc.).
                4. DO NOT import any third-party UI libraries (framer-motion, radix, MUI, recharts, etc.).
                5. If you need icons, ONLY import from 'lucide-react'.
                6. Keep components SIMPLE — use only `useState` and `useEffect`. Do NOT use `useReducer`, `createContext`, `forwardRef`, `memo`, `useCallback`, `useMemo` unless essential.
                7. Do NOT use TypeScript — use plain .jsx files only.
                8. Do NOT use `'use client'` or `'use server'` directives.
                9. If the screenshot includes images, use <img> tags with https://picsum.photos/WIDTH/HEIGHT.
                10. CRITICAL — NO DUPLICATE DECLARATIONS:
                - Each component must be defined EXACTLY ONCE, in its own file.
                - App.jsx must ONLY import and use sub-components.
                COLOR RULE:
                - Select a random primary color family from:
                 red, green, purple, orange, teal, pink, indigo, emerald, amber.
                - Use one dominant primary color family consistently.
                - Do NOT create custom class names like bg-background or text-text.
                """ + multiFileOutput;

            case "bootstrap" -> String.format("""
                Generate a complete <!DOCTYPE html> page with Bootstrap 5 CDN.
                Use Bootstrap grid, components, and custom CSS overrides in <style> tag.
                If the screenshot includes images, implement them using:
                - Standard <img> tags with https://picsum.photos/WIDTH/HEIGHT (no /id/ paths) as placeholders.
                - Explicit width and height attributes
                - No CSS background-image
                COLOR RULE:
                Use this new color palette (do NOT reuse original colors):
                - Primary: %s
                - Secondary: %s
                - Accent: %s
                - Background: %s
                - Text: %s
                """, colors.get("primary"), colors.get("secondary"), colors.get("accent"), colors.get("bg"), colors.get("text")) + singleFileOutput;

            case "tailwind" -> """
                Generate a complete <!DOCTYPE html> page with Tailwind CDN.
                Use Tailwind utility classes and responsive breakpoints.
                If the screenshot includes images, implement them using:
                - Standard <img> tags with https://picsum.photos/WIDTH/HEIGHT (no /id/ paths) as placeholders.
                - Explicit width and height attributes
                - No CSS background-image
                COLOR RULE:
                - Select a random primary color family from:
                 red, green, purple, orange, teal, pink, indigo, emerald, amber.
                - Use one dominant primary color family consistently.- Do NOT create custom class names like bg-background or text-text.
                - Background and text MUST have strong visible contrast.
                - If background is light, text must be dark.
                - If background is dark, text must be light.
                """ + singleFileOutput;

            case "vanilla_js" -> String.format("""
                Generate a complete <!DOCTYPE html> page with CSS in <style> and vanilla ES6+ JavaScript in <script>.
                Event listeners, DOM manipulation, responsive design.
                If the screenshot includes images, implement them using:
                - Standard <img> tags with https://picsum.photos/WIDTH/HEIGHT (no /id/ paths) as placeholders.
                - Explicit width and height attributes
                - No CSS background-image
                COLOR RULE:
                Use this new color palette (do NOT reuse original colors):
                - Primary: %s
                - Secondary: %s
                - Accent: %s
                - Background: %s
                - Text: %s
                """, colors.get("primary"), colors.get("secondary"), colors.get("accent"), colors.get("bg"), colors.get("text")) + singleFileOutput;

            case "vue" -> String.format("""
                Generate Vue 3 components using Composition API and Single File Component structure.
                Split into multiple files: main App.vue + separate component .vue files for distinct UI sections.
                App.vue should import and use all sub-components.
                If the screenshot includes images, implement them using:
                - Standard <img> tags with https://picsum.photos/WIDTH/HEIGHT (no /id/ paths) as placeholders.
                - Explicit width and height attributes
                - No CSS background-image
                COLOR RULE:
                Use this new color palette (do NOT reuse original colors):
                - Primary: %s
                - Secondary: %s
                - Accent: %s
                - Background: %s
                - Text: %s
                """, colors.get("primary"), colors.get("secondary"), colors.get("accent"), colors.get("bg"), colors.get("text")) + multiFileOutput;

            case "svelte" -> String.format("""
                Generate Svelte components with reactive statements.
                Split into multiple files: main App.svelte + separate .svelte component files.
                App.svelte should import and compose all sub-components.
                IMPORTANT FILE STRUCTURE:
                - ALL .svelte files must be at the ROOT level (no subdirectories like components/).
                - Filenames: App.svelte, Header.svelte, UserTable.svelte, etc.
                - Imports MUST use flat paths: `import Header from './Header.svelte';` (NOT `./components/Header.svelte`)
                
                STRICT SVELTE RULES:
                1. For local component state, use plain `let` variables. Example: `let email = '';`
                2. NEVER use the `$` prefix on plain variables. `$variable` syntax ONLY works with Svelte stores.
                   - WRONG: `let email = ''; ... {$email}` — CRASHES with "subscribe is not a function"
                   - CORRECT: `let email = ''; ... {email}` — use the variable name directly
                3. Use `$:` reactive statements for derived values. Example: `$: fullName = firstName + ' ' + lastName;`
                4. Use `bind:value` for two-way binding on inputs.
                5. Use `on:click`, `on:submit` for event handlers — NOT `onClick` or `onSubmit`.
                6. Use `{#if}`, `{#each}`, `{:else}` for control flow — NOT JSX conditionals.
                7. Do NOT import React, useState, or any React APIs.
                8. CSS should be inside `<style>` tags in each `.svelte` file.
                9. Do NOT import anything from 'svelte' or 'svelte/store'. This includes:
                   - NO createEventDispatcher (use on:click callbacks passed as props instead)
                   - NO onMount, onDestroy, beforeUpdate, afterUpdate
                   - NO writable, readable, derived
                   - NO tick, setContext, getContext
                   Use plain `let` variables, `$:` reactive statements, and direct event handlers only.
                10. Keep components simple — max 4 .svelte files total including App.svelte.
                11. For parent-child communication: use props (`export let handler`) instead of createEventDispatcher.
                12. NEVER use React JSX attributes — Svelte uses plain HTML:
                   - WRONG: `className="..."` → CORRECT: `class="..."`
                   - WRONG: `onClick={fn}` → CORRECT: `on:click={fn}`
                   - WRONG: `onChange={fn}` → CORRECT: `on:change={fn}`
                   - WRONG: `onSubmit={fn}` → CORRECT: `on:submit={fn}`
                   - WRONG: `htmlFor="id"` → CORRECT: `for="id"`
                   - WRONG: `tabIndex={0}` → CORRECT: `tabindex={0}`
                   - SVG attributes use kebab-case: `stroke-width`, `fill-opacity`
                
                If the screenshot includes images, implement them using:
                - Standard <img> tags with https://picsum.photos/WIDTH/HEIGHT (no /id/ paths) as placeholders.
                - Explicit width and height attributes
                - No CSS background-image
                COLOR RULE:
                Use this new color palette (do NOT reuse original colors):
                - Primary: %s
                - Secondary: %s
                - Accent: %s
                - Background: %s
                - Text: %s
                """, colors.get("primary"), colors.get("secondary"), colors.get("accent"), colors.get("bg"), colors.get("text")) + multiFileOutput;

            case "next_js" -> """
                Generate Next.js components using standard React functional syntax.
                Split into: app/page.jsx (main entry) + max 3 component files.
                
                STRICT RULES:
                1. ONLY use Tailwind CSS utility classes.
                2. ALWAYS use `className=` instead of `class=`.
                3. DO NOT import any external UI libraries (framer-motion, headlessui, recharts, etc.).
                4. If you need icons, ONLY import from 'lucide-react'.
                5. DO NOT use server-side features: no `metadata`, `'use server'`, `getServerSideProps`, `getStaticProps`.
                6. DO NOT use `'use client'` directive.
                7. DO NOT use `useRouter`, `usePathname`, `useSearchParams`, or any next/navigation imports.
                8. Use .jsx files ONLY — do NOT use TypeScript (.tsx). No type annotations, no interfaces.
                9. Each component must be defined EXACTLY ONCE in its own file.
                10. Keep components simple — use only `useState` and `useEffect`.
                11. If the screenshot includes images, use <img> tags with https://picsum.photos/WIDTH/HEIGHT.
                12. Treat this as a pure CLIENT-SIDE React app. No server features.
                
                CRITICAL — THIS IS A REACT APP, NOT VUE:
                - Do NOT generate .vue files
                - Do NOT use <template>, <script setup>, defineComponent, ref(), reactive()
                - Do NOT generate components/icon/*.vue or any Vue-style paths
                - ONLY generate .jsx files with React JSX syntax
                
                COLOR RULE:
                - Select a random primary color family from:
                 red, green, purple, orange, teal, pink, indigo, emerald, amber.
                - Use one dominant primary color family consistently.
                - Do NOT create custom class names like bg-background or text-text.
                
                CRITICAL: Return FULL code for EVERY component. Never truncate.
                """ + multiFileOutput;

            case "nuxt_js" -> String.format("""
                Generate Vue 3 components using Composition API and Single File Component structure.
                Split into: main App.vue + max 3 separate .vue component files.
                App.vue should import and use all sub-components.
                
                STRICT RULES:
                1. Use Vue 3 Composition API with `<script setup>` syntax.
                2. Do NOT use Nuxt-specific features: no `useFetch`, `useAsyncData`, `defineNuxtConfig`, `<NuxtPage>`, `<NuxtLink>`.
                3. Use standard `<a>` tags for links and `<img>` for images.
                4. Do NOT import from 'nuxt/app' or any nuxt modules.
                5. Treat this as a standard Vue 3 app.
                6. CSS should be in `<style scoped>` blocks.
                7. If the screenshot includes images, use <img> tags with https://picsum.photos/WIDTH/HEIGHT.
                
                COLOR RULE:
                Use this new color palette (do NOT reuse original colors):
                - Primary: %s
                - Secondary: %s
                - Accent: %s
                - Background: %s
                - Text: %s
                """, colors.get("primary"), colors.get("secondary"), colors.get("accent"), colors.get("bg"), colors.get("text")) + multiFileOutput;

            default -> singleFileOutput;
        };

        return basePrompt + frameworkRules;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // create_chat_prompt() — 100% identical logic to server.py
    // Single giant text block. Intents: MODIFY, CLARIFY, EXPLAIN, UNRELATED
    // ─────────────────────────────────────────────────────────────────────────────

    private String buildChatPrompt(String code, String message, String framework,
                                   List<Map<String, Object>> chatHistory) {

        // Format files for the prompt — mirrors server.py create_chat_prompt()
        String filesText;
        try {
            Object parsed = objectMapper.readValue(code, Object.class);
            if (parsed instanceof List<?> files) {
                StringBuilder sb = new StringBuilder();
                for (Object f : files) {
                    if (f instanceof Map<?, ?> fileMap) {
                        sb.append("\n--- FILE: ").append(fileMap.get("filename")).append(" ---\n");
                        sb.append(fileMap.get("content")).append("\n");
                    }
                }
                filesText = sb.toString();
            } else {
                filesText = "\n" + code + "\n";
            }
        } catch (Exception e) {
            filesText = "\n" + code + "\n";
        }

        // Determine multi-file
        boolean isMultiFile = Set.of("react", "vue", "svelte", "next_js", "nuxt_js").contains(framework);

        String codeFormatInstruction;
        if (isMultiFile) {
            codeFormatInstruction = """
                - "code" MUST be a JSON array: [{"filename": "...", "content": "..."}]
                - Include ALL files (modified + unmodified). Never omit files.
                - Each file's "content" must be COMPLETE, never partial.""";
        } else {
            codeFormatInstruction = """
                - "code" MUST be the COMPLETE source code as a single string.
                - Never return partial snippets or placeholders.""";
        }

        // Format conversation history (last 5 messages) — mirrors server.py
        StringBuilder historyText = new StringBuilder();
        if (chatHistory != null && !chatHistory.isEmpty()) {
            List<Map<String, Object>> recent = chatHistory.subList(
                Math.max(0, chatHistory.size() - 5), chatHistory.size());
            historyText.append("\n## RECENT CONVERSATION\n");
            for (Map<String, Object> msg : recent) {
                String role = String.valueOf(msg.getOrDefault("role", "user")).toUpperCase();
                String content = String.valueOf(msg.getOrDefault("content", ""));
                historyText.append(role).append(": ").append(content).append("\n");
            }
        }

        // Build the full prompt — identical structure to server.py create_chat_prompt()
        return String.format("""
            You are a helpful AI coding assistant for a %s project. You help users understand and modify their code.
            
            ## CURRENT PROJECT FILES
            %s
            %s
            ## USER MESSAGE
            "%s"
            
            ## HOW TO RESPOND
            
            Classify the user's intent and respond with a single JSON object:
            
            {
                "intent": "MODIFY" | "CLARIFY" | "EXPLAIN" | "UNRELATED",
                "message": "your response",
                "code": <modified code or null>
            }
            
            ### Intent Guide:
            
            **MODIFY** — User wants a code change (add, fix, update, remove, change, make, create).
            %s
            - Change ONLY what the user asked for. Do NOT touch unrelated code.
            - Do NOT add libraries or dependencies not already in the project.
            - "message" MUST clearly explain what you changed and why. Never reply with just "Code updated." — describe the specific changes.
            - If the user asks to fix an error but doesn't provide the error message, use CLARIFY instead.
            - Preserve all existing files, components, and structure.
            
            **CLARIFY** — You need more information before you can help. Use this when:
            - User asks to "fix the error" but didn't paste the error message
            - User asks to add images but didn't provide URLs or specify what images
            - User's request is ambiguous and could mean multiple things
            - "code" MUST be null for CLARIFY.
            - "message" should ask a specific question to get the info you need. Be friendly and direct.
            
            **EXPLAIN** — User asks a question about the code without requesting changes.
            - "code" MUST be null.
            - Give a clear, well-structured explanation. Use bullet points and backticks for code terms.
            
            **UNRELATED** — Message has nothing to do with coding or the project.
            - "code" MUST be null.
            - "message": "I can only help with understanding or modifying your %s project code. What would you like to do?"
            
            ### Image Requests:
            If the user asks to add, change, or replace images:
            - ALWAYS use CLARIFY intent. Ask the user to provide the exact image URL(s) they want.
            - Do NOT insert placeholder images, random URLs, or any image API URLs.
            - Example response: "Sure! Please provide the image URL you'd like me to use, and I'll update the code for you."
            - Only use MODIFY with an image if the user has already provided a specific URL in this message or in the recent conversation.
            
            ## OUTPUT RULES
            1. Return ONLY the raw JSON object. No markdown, no code fences.
            2. Must be parseable by json.loads().
            3. "code" must be valid code or null — never an empty string.
            """,
            framework, filesText, historyText.toString(), message,
            codeFormatInstruction, framework);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // generate_random_colors() — identical palettes to server.py
    // ─────────────────────────────────────────────────────────────────────────────

    private Map<String, String> generateRandomColors() {
        List<Map<String, String>> palettes = List.of(
            Map.of("primary", "#6366f1", "secondary", "#4f46e5", "accent", "#818cf8", "bg", "#0f172a", "text", "#f8fafc"),
            Map.of("primary", "#ec4899", "secondary", "#db2777", "accent", "#f472b6", "bg", "#18181b", "text", "#fafafa"),
            Map.of("primary", "#06b6d4", "secondary", "#0891b2", "accent", "#22d3ee", "bg", "#020617", "text", "#f1f5f9"),
            Map.of("primary", "#10b981", "secondary", "#059669", "accent", "#34d399", "bg", "#050505", "text", "#ffffff")
        );
        return palettes.get(new Random().nextInt(palettes.size()));
    }

    private void validateFramework(String framework) {
        if (!ALLOWED_FRAMEWORKS.contains(framework)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid framework. Allowed: " + ALLOWED_FRAMEWORKS);
        }
    }
}
