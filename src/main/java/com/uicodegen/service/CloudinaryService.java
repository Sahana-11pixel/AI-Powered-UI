package com.uicodegen.service;

import com.cloudinary.Cloudinary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Handles image uploads to Cloudinary — mirrors Python's cloudinary.uploader.upload() call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloudinaryService {

    private final Cloudinary cloudinary;

    /**
     * Upload image bytes to Cloudinary and return the secure URL.
     */
    @SuppressWarnings("unchecked")
    public String uploadImage(byte[] imageBytes) {
        try {
            Map<String, Object> result = cloudinary.uploader().upload(imageBytes, Map.of(
                "folder", "ui5_screenshots",
                "resource_type", "image"
            ));
            String url = (String) result.get("secure_url");
            log.info("Image uploaded to Cloudinary: {}", url);
            return url;
        } catch (Exception e) {
            log.error("Cloudinary upload failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to upload image to cloud storage.");
        }
    }
}
