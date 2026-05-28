package com.uicodegen.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

/**
 * MongoDB User document — mirrors the Python user_doc structure exactly.
 * Fields match 1-to-1 so the same MongoDB Atlas database works for both backends.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class User {

    @Id
    private String mongoId;          // MongoDB internal _id

    @Indexed(unique = true)
    private String id;               // UUID string (used in JWT payload)

    private String firebaseUid;      // Firebase UID (nullable for bcrypt users)

    private String name;
    private String email;
    private String passwordHash;     // BCrypt hash (empty string for Firebase users)
    private String role;             // "user" | "admin"
    private String createdAt;        // ISO-8601 string
    private String lastLogin;        // ISO-8601 string
    private String updatedAt;

    @Builder.Default
    private boolean isDeleted = false;

    private String deletedAt;
    private boolean isActive;
}
