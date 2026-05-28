package com.uicodegen.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uicodegen.controller.UploadController;
import com.uicodegen.dto.request.GenerateRequest;
import com.uicodegen.dto.response.GenerateResponse;
import com.uicodegen.model.ApiUsage;
import com.uicodegen.repository.ApiUsageRepository;
import com.uicodegen.security.UserPrincipal;
import com.uicodegen.service.CodeSafetyService;
import com.uicodegen.service.GeminiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Generate", description = "AI code generation endpoint")
public class GenerateController {

    private final GeminiService geminiService;
    private final CodeSafetyService codeSafetyService;
    private final ApiUsageRepository apiUsageRepository;
    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/generate")
    @Operation(summary = "Generate UI code from uploaded screenshot")
    public GenerateResponse generateCode(
            @RequestBody GenerateRequest req,
            @AuthenticationPrincipal UserPrincipal user) {

        req.validate();

        // 1. Fetch image bytes from cache (primary) or Cloudinary URL (fallback)
        byte[] imageBytes = UploadController.IMAGE_CACHE.get(req.getImageId());

        if (imageBytes == null && req.getImageUrl() != null && !req.getImageUrl().isBlank()) {
            log.info("Cache miss for {}, downloading from URL", req.getImageId());
            imageBytes = downloadFromUrl(req.getImageUrl());
            if (imageBytes != null) {
                UploadController.IMAGE_CACHE.put(req.getImageId(), imageBytes);
            }
        }

        if (imageBytes == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Image not found. Please re-upload the screenshot.");
        }

        // 2. Call Gemini AI
        String rawOutput = geminiService.generateCode(imageBytes, req.getFramework());

        // 3. Run code safety pipeline
        String cleanedJson = codeSafetyService.runPipeline(rawOutput, req.getFramework());

        // 4. Build preview HTML
        List<Map<String, Object>> files = codeSafetyService.parseFilesJson(cleanedJson, req.getFramework());
        String previewHtml = codeSafetyService.buildPreviewHtml(files, req.getFramework());

        // 5. Remove from cache (consumed)
        UploadController.IMAGE_CACHE.remove(req.getImageId());

        // 6. Log API usage
        apiUsageRepository.save(ApiUsage.builder()
            .id(UUID.randomUUID().toString())
            .userId(user.getUserId())
            .action("generate")
            .framework(req.getFramework())
            .timestamp(Instant.now().toString())
            .tokenUsage(0)
            .build());

        log.info("Code generated for user {} framework {}", user.getUserId(), req.getFramework());

        return GenerateResponse.builder()
                .code(cleanedJson)
                .previewHtml(previewHtml)
                .framework(req.getFramework())
                .message("Code generated successfully")
                .build();
    }

    private byte[] downloadFromUrl(String url) {
        try {
            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().bytes();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to download image from URL: {}", e.getMessage());
        }
        return null;
    }
}
