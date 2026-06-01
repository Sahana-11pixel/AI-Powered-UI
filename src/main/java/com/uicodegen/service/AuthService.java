package com.uicodegen.service;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserRecord;
import com.uicodegen.dto.request.FirebaseLoginRequest;
import com.uicodegen.dto.request.LoginRequest;
import com.uicodegen.dto.request.SignupRequest;
import com.uicodegen.dto.response.LoginResponse;
import com.uicodegen.dto.response.UserResponse;
import com.uicodegen.model.User;
import com.uicodegen.repository.UserRepository;
import com.uicodegen.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Auth service — mirrors all Python auth endpoint logic:
 *   signup, login, firebase-login, reset-password, delete-account, update-profile, get-me
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    // ──────────────────────────────────────────────
    // POST /api/auth/signup
    // ──────────────────────────────────────────────
    public LoginResponse signup(SignupRequest req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email already registered");
        }

        String userId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        User user = User.builder()
                .id(userId)
                .name(req.getName())
                .email(req.getEmail())
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .role("user")
                .createdAt(now)
                .lastLogin(now)
                .isDeleted(false)
                .build();

        userRepository.save(user);
        log.info("New user registered: {}", req.getEmail());

        String token = jwtUtil.createToken(userId, req.getEmail(), "user");
        return buildLoginResponse(token, user);
    }

    // ──────────────────────────────────────────────
    // POST /api/auth/login
    // ──────────────────────────────────────────────
    public LoginResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        // Update last login
        Query q = Query.query(Criteria.where("email").is(req.getEmail()));
        mongoTemplate.updateFirst(q, Update.update("lastLogin", Instant.now().toString()), User.class);

        log.info("User logged in: {}", req.getEmail());
        String token = jwtUtil.createToken(user.getId(), user.getEmail(), user.getRole());
        return buildLoginResponse(token, user);
    }

    // ──────────────────────────────────────────────
    // POST /api/auth/firebase-login
    // ──────────────────────────────────────────────
    public LoginResponse firebaseLogin(FirebaseLoginRequest req) {
        try {
            // 1. Verify Firebase ID token
            FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(req.getIdToken());
            String firebaseUid = decoded.getUid();
            String email = decoded.getEmail();

            // 2. Check email_verified from live user record (not cached token)
            boolean emailVerified;
            try {
                UserRecord firebaseUser = FirebaseAuth.getInstance().getUser(firebaseUid);
                emailVerified = firebaseUser.isEmailVerified();
            } catch (Exception e) {
                emailVerified = decoded.isEmailVerified();
            }

            if (!emailVerified) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Email not verified. Please verify your email before logging in.");
            }

            // 3. Find or create user in MongoDB
            String now = Instant.now().toString();
            Optional<User> existingOpt = userRepository.findByEmail(email);

            User user;
            if (existingOpt.isPresent()) {
                user = existingOpt.get();
                // If user was soft-deleted but is now logging in via a valid Firebase token, resurrect them
                if (user.isDeleted()) {
                    log.info("Resurrecting soft-deleted user via Firebase login: {}", email);
                    user.setDeleted(false);
                }
                // Update last login & ensure they are active
                Query q = Query.query(Criteria.where("email").is(email));
                mongoTemplate.updateFirst(q,
                    new Update()
                        .set("lastLogin", now)
                        .set("firebaseUid", firebaseUid)
                        .set("isDeleted", false)
                        .set("isActive", true), 
                    User.class);
            } else {
                // Create new user
                String userId = UUID.randomUUID().toString();
                String name = req.getName() != null ? req.getName()
                            : decoded.getName() != null ? decoded.getName()
                            : email.split("@")[0];

                user = User.builder()
                        .id(userId)
                        .firebaseUid(firebaseUid)
                        .name(name)
                        .email(email)
                        .passwordHash("")
                        .role("user")
                        .createdAt(now)
                        .lastLogin(now)
                        .isDeleted(false)
                        .build();

                userRepository.save(user);
                log.info("New Firebase user created: {}", email);
            }

            // 4. Generate our own JWT
            String token = jwtUtil.createToken(user.getId(), email, user.getRole());
            log.info("Firebase login successful: {}", email);
            return buildLoginResponse(token, user);

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Firebase login error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired Firebase token");
        }
    }

    // ──────────────────────────────────────────────
    // POST /api/auth/reset-password
    // ──────────────────────────────────────────────
    public void resetPassword(String userId, String currentPassword, String newPassword) {
        User user = findUserById(userId);

        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
        }

        Query q = Query.query(Criteria.where("id").is(userId));
        mongoTemplate.updateFirst(q,
            new Update().set("passwordHash", passwordEncoder.encode(newPassword))
                        .set("updatedAt", Instant.now().toString()),
            User.class);

        log.info("Password reset for user: {}", user.getEmail());
    }

    // ──────────────────────────────────────────────
    // DELETE /api/auth/account
    // ──────────────────────────────────────────────
    public void softDeleteAccount(String userId) {
        findUserById(userId); // validates user exists

        String now = Instant.now().toString();
        Query q = Query.query(Criteria.where("id").is(userId));
        mongoTemplate.updateFirst(q,
            new Update().set("isDeleted", true).set("deletedAt", now).set("isActive", false),
            User.class);

        log.info("Account soft-deleted: {}", userId);
    }

    // ──────────────────────────────────────────────
    // PUT /api/auth/profile
    // ──────────────────────────────────────────────
    public String updateProfile(String userId, String name) {
        findUserById(userId);

        Query q = Query.query(Criteria.where("id").is(userId));
        mongoTemplate.updateFirst(q,
            new Update().set("name", name).set("updatedAt", Instant.now().toString()),
            User.class);

        log.info("User {} updated name to {}", userId, name);
        return name;
    }

    // ──────────────────────────────────────────────
    // GET /api/auth/me
    // ──────────────────────────────────────────────
    public UserResponse getMe(String userId) {
        User user = findUserById(userId);
        return toUserResponse(user);
    }

    // ─── Helpers ───────────────────────────────────

    private User findUserById(String userId) {
        Query q = Query.query(Criteria.where("id").is(userId));
        User user = mongoTemplate.findOne(q, User.class);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        return user;
    }

    private LoginResponse buildLoginResponse(String token, User user) {
        return LoginResponse.builder()
                .token(token)
                .access_token(token)
                .token_type("bearer")
                .user(toUserResponse(user))
                .build();
    }

    public UserResponse toUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .isDeleted(user.isDeleted())
                .build();
    }
}
