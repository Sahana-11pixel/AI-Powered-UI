package com.uicodegen.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ports the Python code safety pipeline to Java:
 *   post_process_code() → validate_generated_code() → repair_code_with_ai()
 *
 * Pipeline: raw AI output → post-process → validate → AI repair (if needed) → final JSON
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeSafetyService {

    private final GeminiService geminiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> JSX_FRAMEWORKS = Set.of("react", "next_js");
    private static final Set<String> MULTI_FILE_FRAMEWORKS = Set.of("react", "vue", "svelte", "next_js", "nuxt_js");

    /**
     * Master pipeline entry point.
     * Takes raw Gemini output JSON string, returns cleaned JSON string.
     */
    public String runPipeline(String rawFilesJson, String framework) {
        try {
            List<Map<String, Object>> files = parseFilesJson(rawFilesJson, framework);
            if (files.isEmpty()) return "[]";

            // Step 1: Post-process
            files = postProcess(files, framework);
            log.info("Post-processing complete for {} file(s)", files.size());

            // Step 2: Validate
            List<String> errors = validate(files, framework);
            if (!errors.isEmpty()) {
                log.warn("Validation found {} error(s): {}", errors.size(), errors);

                // Step 3: AI repair
                try {
                    String repairedJson = geminiService.repairCode(
                        objectMapper.writeValueAsString(files), framework, errors);
                    List<Map<String, Object>> repaired = parseFilesJson(repairedJson, framework);
                    files = postProcess(repaired, framework);
                    log.info("AI repair completed");
                } catch (Exception e) {
                    log.warn("AI repair failed, using post-processed original: {}", e.getMessage());
                }
            }

            return objectMapper.writeValueAsString(files);

        } catch (Exception e) {
            log.error("Code safety pipeline error: {}", e.getMessage());
            return rawFilesJson;
        }
    }

    /**
     * Mirrors Python parse_generated_output().
     * Strips markdown fences, tries JSON array parse, handles wrapped objects,
     * and falls back to wrapping in a single-file array.
     * Returns JSON string: [{"filename":"...","content":"..."}]
     */
    public String parseGeneratedOutput(String rawOutput, String framework) {
        String cleaned = rawOutput != null ? rawOutput.strip() : "";

        // Strip markdown fences — mirrors server.py lines 694-704
        if (cleaned.contains("```")) {
            cleaned = stripMarkdownFences(cleaned);
        }

        // Try to parse as JSON array — mirrors server.py lines 707-723
        try {
            Object parsed = objectMapper.readValue(cleaned, Object.class);
            if (parsed instanceof List<?> list && !list.isEmpty()) {
                boolean valid = ((List<?>) parsed).stream().allMatch(
                    f -> f instanceof Map<?, ?> m && m.containsKey("filename") && m.containsKey("content")
                );
                if (valid) return objectMapper.writeValueAsString(parsed);
            }
            // Handle wrapped: {"intent":"MODIFY","code":[...]}
            if (parsed instanceof Map<?, ?> map && map.containsKey("code")) {
                Object codeField = map.get("code");
                if (codeField instanceof List<?>) {
                    return objectMapper.writeValueAsString(codeField);
                } else if (codeField instanceof String codeStr) {
                    return parseGeneratedOutput(codeStr, framework);
                }
            }
        } catch (Exception ignored) {}

        // Single file fallback — mirrors server.py lines 726-727
        String filename = getDefaultFilename(framework);
        try {
            return objectMapper.writeValueAsString(List.of(Map.of("filename", filename, "content", cleaned)));
        } catch (Exception e) {
            return "[{\"filename\":\"" + filename + "\",\"content\":\"\"}]";
        }
    }

    /**
     * Parse raw AI output into a files list.
     * Handles both JSON array output (multi-file) and raw code (single-file).
     */
    public List<Map<String, Object>> parseFilesJson(String raw, String framework) {
        String cleaned = raw.strip();

        // Strip markdown fences
        if (cleaned.contains("```")) {
            cleaned = stripMarkdownFences(cleaned);
        }

        // Try parsing as JSON array
        try {
            Object parsed = objectMapper.readValue(cleaned, Object.class);
            if (parsed instanceof List<?> list && !list.isEmpty()) {
                List<Map<String, Object>> files = objectMapper.convertValue(
                    parsed, new TypeReference<>() {});
                if (files.stream().allMatch(f -> f.containsKey("filename") && f.containsKey("content"))) {
                    return files;
                }
            }
            // Handle wrapped response: {"code": [...]}
            if (parsed instanceof Map<?,?> map && map.containsKey("code")) {
                Object codeField = map.get("code");
                if (codeField instanceof List<?>) {
                    List<Map<String, Object>> files = objectMapper.convertValue(
                        codeField, new TypeReference<>() {});
                    return files;
                } else if (codeField instanceof String codeStr) {
                    return parseFilesJson(codeStr, framework);
                }
            }
        } catch (Exception ignored) {}

        // Fallback: treat as single-file raw code
        String filename = getDefaultFilename(framework);
        return List.of(Map.of("filename", filename, "content", cleaned));
    }

    // ─── Post-processor ───────────────────────────────────────────────────────

    private List<Map<String, Object>> postProcess(List<Map<String, Object>> files, String framework) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> file : files) {
            String filename = (String) file.get("filename");
            String content  = (String) file.get("content");
            if (content == null || content.isBlank()) { result.add(file); continue; }

            // Remove BOM and control chars
            content = content.replaceAll("[\\uFEFF\\u200B\\u200C\\u200D\\u2060]", "");
            content = content.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
            // Normalize smart quotes
            content = content.replace("\u201C", "\"").replace("\u201D", "\"");
            content = content.replace("\u2018", "'").replace("\u2019", "'");

            // Strip markdown fences
            if (content.strip().startsWith("```")) {
                content = stripMarkdownFences(content);
            }

            // JSX fixes
            if (JSX_FRAMEWORKS.contains(framework) &&
                    (filename.endsWith(".jsx") || filename.endsWith(".js") || filename.endsWith(".tsx"))) {
                // class= → className=
                content = content.replaceAll("(?<![a-zA-Z])class=(\")", "className=$1");
                content = content.replaceAll("(?<![a-zA-Z])class=(')", "className=$1");
                content = content.replaceAll("(?<![a-zA-Z])class=\\{", "className={");
                // Remove 'use client'/'use server'
                content = content.replaceAll("['\"]use (client|server)['\"];?\\s*\\n?", "");
                // Strip TS annotations
                content = content.replaceAll(":\\s*(string|number|boolean|void|any|null|undefined)\\b", "");
            }

            // Svelte fixes
            if ("svelte".equals(framework) && filename.endsWith(".svelte")) {
                content = content.replaceAll("\\bclassName=", "class=");
                content = content.replaceAll("\\bonClick=", "on:click=");
                content = content.replaceAll("\\bonChange=", "on:change=");
                content = content.replaceAll("\\bonSubmit=", "on:submit=");
                content = content.replaceAll("\\bhtmlFor=", "for=");
                // Flatten nested import paths
                content = content.replaceAll(
                    "(import\\s+\\w+\\s+from\\s+['\"])\\./(?:components|lib)/([^'\"]+['\"])",
                    "$1./$2");
                if (filename.contains("/")) {
                    filename = filename.substring(filename.lastIndexOf('/') + 1);
                }
            }

            content = content.stripTrailing();
            Map<String, Object> processed = new LinkedHashMap<>(file);
            processed.put("filename", filename);
            processed.put("content", content);
            result.add(processed);
        }
        return result;
    }

    // ─── Validator ────────────────────────────────────────────────────────────

    private List<String> validate(List<Map<String, Object>> files, String framework) {
        List<String> errors = new ArrayList<>();

        for (Map<String, Object> file : files) {
            String filename = (String) file.get("filename");
            String content  = (String) file.get("content");
            if (content == null || content.isBlank()) continue;

            // Bracket balance check
            errors.addAll(checkBracketBalance(filename, content));

            // JSX-specific: check for remaining class=
            if (JSX_FRAMEWORKS.contains(framework) &&
                    (filename.endsWith(".jsx") || filename.endsWith(".js"))) {
                Matcher m = Pattern.compile("(?<![a-zA-Z])class=\"").matcher(content);
                int count = 0;
                while (m.find()) count++;
                if (count > 0) {
                    errors.add(filename + ": Found " + count + " instance(s) of class= (should be className=)");
                }
            }

            // Vue: check template block
            if (Set.of("vue", "nuxt_js").contains(framework) && filename.endsWith(".vue")) {
                if (!content.contains("<template>")) {
                    errors.add(filename + ": Missing <template> block in Vue component");
                }
            }
        }

        // React/Next.js: cross-file duplicate declaration check
        if (JSX_FRAMEWORKS.contains(framework) && files.size() > 1) {
            Map<String, List<String>> declarations = new HashMap<>();
            for (Map<String, Object> file : files) {
                String fn = (String) file.get("filename");
                String content = (String) file.get("content");
                if (content == null || !fn.endsWith(".jsx")) continue;
                Pattern p = Pattern.compile("^(?:export\\s+(?:default\\s+)?)?(?:function|class|const)\\s+([A-Z][a-zA-Z0-9_$]*)\\s*[=(\\{]",
                    Pattern.MULTILINE);
                Matcher m = p.matcher(content);
                while (m.find()) {
                    declarations.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(fn);
                }
            }
            declarations.forEach((name, filenames) -> {
                if (filenames.size() > 1) {
                    errors.add("Duplicate component '" + name + "' declared in: " + String.join(", ", filenames));
                }
            });
        }

        return errors;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private List<String> checkBracketBalance(String filename, String content) {
        List<String> errors = new ArrayList<>();
        Deque<Character> stack = new ArrayDeque<>();
        Map<Character, Character> pairs = Map.of('(', ')', '[', ']', '{', '}');
        boolean inString = false;
        char stringChar = 0;
        boolean escaped = false;

        for (char ch : content.toCharArray()) {
            if (escaped) { escaped = false; continue; }
            if (ch == '\\') { escaped = true; continue; }
            if (inString) {
                if (ch == stringChar) inString = false;
                continue;
            }
            if (ch == '"' || ch == '\'' || ch == '`') {
                inString = true;
                stringChar = ch;
                continue;
            }
            if (pairs.containsKey(ch)) {
                stack.push(pairs.get(ch));
            } else if (pairs.containsValue(ch)) {
                if (stack.isEmpty() || stack.peek() != ch) {
                    errors.add(filename + ": Unbalanced bracket '" + ch + "'");
                    break;
                }
                stack.pop();
            }
        }
        if (!stack.isEmpty()) {
            errors.add(filename + ": " + stack.size() + " unclosed bracket(s)");
        }
        return errors;
    }

    private String stripMarkdownFences(String content) {
        String[] lines = content.split("\n");
        List<String> codeLines = new ArrayList<>();
        boolean inBlock = false;
        for (String line : lines) {
            if (line.strip().startsWith("```")) {
                inBlock = !inBlock;
                continue;
            }
            if (inBlock) codeLines.add(line);
        }
        String result = String.join("\n", codeLines).strip();
        return result.isBlank() ? content : result;
    }

    private String getDefaultFilename(String framework) {
        return switch (framework) {
            case "react"    -> "App.jsx";
            case "vue"      -> "App.vue";
            case "svelte"   -> "App.svelte";
            case "next_js"  -> "app/page.jsx";
            case "nuxt_js"  -> "app.vue";
            default         -> "index.html";
        };
    }

    /**
     * Build preview HTML for React/Vue/Svelte so the frontend iframe can render it.
     * Mirrors Python's create_react_preview_html / create_vue_preview_html etc.
     */
    public String buildPreviewHtml(List<Map<String, Object>> files, String framework) {
        if (files.isEmpty()) return "";
        String mainContent = (String) files.get(0).get("content");

        return switch (framework) {
            case "react", "next_js" -> """
                <!DOCTYPE html><html lang="en"><head>
                <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
                <script crossorigin src="https://unpkg.com/react@18/umd/react.production.min.js"></script>
                <script crossorigin src="https://unpkg.com/react-dom@18/umd/react-dom.production.min.js"></script>
                <script src="https://unpkg.com/@babel/standalone/babel.min.js"></script>
                <style>* { margin: 0; padding: 0; box-sizing: border-box; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }</style>
                </head><body><div id="root"></div>
                <script type="text/babel">
                """ + mainContent + """
                const root = ReactDOM.createRoot(document.getElementById('root'));
                root.render(<App />);
                </script></body></html>
                """;
            case "vue", "nuxt_js" -> """
                <!DOCTYPE html><html lang="en"><head>
                <meta charset="UTF-8">
                <script src="https://unpkg.com/vue@3/dist/vue.global.js"></script>
                </head><body><div id="app"></div>
                <script>const { createApp } = Vue;
                """ + mainContent + "createApp(App).mount('#app');</script></body></html>";
            default -> mainContent; // html_css, tailwind, bootstrap, vanilla_js — already full HTML
        };
    }
}
