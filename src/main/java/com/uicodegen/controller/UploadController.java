package com.uicodegen.controller;

import com.uicodegen.model.ApiUsage;
import com.uicodegen.repository.ApiUsageRepository;
import com.uicodegen.security.UserPrincipal;
import com.uicodegen.service.CloudinaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Upload", description = "Image upload endpoint")
public class UploadController {

    private final CloudinaryService cloudinaryService;
    private final ApiUsageRepository apiUsageRepository;

    /** Shared in-memory image cache: imageId → PNG bytes. Mirrors Python _image_cache. */
    public static final Map<String, byte[]> IMAGE_CACHE = new ConcurrentHashMap<>();

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final String[] ALLOWED_TYPES = {"image/png", "image/jpeg", "image/jpg"};

    @PostMapping("/upload")
    @Operation(summary = "Upload a UI screenshot")
    public ResponseEntity<Map<String, Object>> uploadScreenshot(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal user) {

        // Validate content type
        String contentType = file.getContentType();
        if (contentType == null || Arrays.stream(ALLOWED_TYPES).noneMatch(contentType::equals)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid file type. Only PNG and JPEG are supported.");
        }

        // Validate file size
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "File too large. Maximum size is 10MB.");
        }

        try {
            byte[] rawBytes = file.getBytes();

            // Validate it's a real image using Java ImageIO
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (img == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid image file.");
            }

            // Basic UI validation — reject solid-color/blank images (mirrors Python validate_image_ui)
            if (!hasVisualContent(img)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No UI detected. Please upload a valid website or app UI screenshot.");
            }

            // Convert to PNG bytes for consistent storage
            ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", pngOut);
            byte[] pngBytes = pngOut.toByteArray();

            // Upload to Cloudinary
            String imageUrl = cloudinaryService.uploadImage(pngBytes);

            // Cache in memory for fast Gemini access
            String imageId = UUID.randomUUID().toString();
            IMAGE_CACHE.put(imageId, pngBytes);

            // Schedule cache cleanup after 10 min (simple thread-based approach)
            String idToClear = imageId;
            Thread.ofVirtual().start(() -> {
                try { Thread.sleep(600_000); } catch (InterruptedException ignored) {}
                IMAGE_CACHE.remove(idToClear);
                log.debug("Auto-cleaned image cache: {}", idToClear);
            });

            // Log API usage
            apiUsageRepository.save(ApiUsage.builder()
                .id(UUID.randomUUID().toString())
                .userId(user.getUserId())
                .action("upload")
                .timestamp(Instant.now().toString())
                .tokenUsage(0)
                .build());

            log.info("Image uploaded: {} for user {}", imageId, user.getUserId());
            return ResponseEntity.ok(Map.of(
                "image_id",  imageId,
                "image_url", imageUrl,
                "message",   "Image uploaded successfully"
            ));

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Upload error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Upload failed: " + e.getMessage());
        }
    }

    /** Mirrors Python validate_image_ui — rejects images where 99.9% pixels are the same color */
    private boolean hasVisualContent(BufferedImage img) {
        int width = img.getWidth(), height = img.getHeight();
        if (width < 100 || height < 100) return false;

        int[] histogram = new int[256];
        int total = width * height;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                int gray = (int)(0.299 * r + 0.587 * g + 0.114 * b);
                histogram[gray]++;
            }
        }
        int maxCount = Arrays.stream(histogram).max().orElse(0);
        return (double) maxCount / total <= 0.999;
    }
}
