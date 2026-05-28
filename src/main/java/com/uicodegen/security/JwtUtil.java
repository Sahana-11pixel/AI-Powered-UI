package com.uicodegen.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT utility — mirrors Python's create_jwt_token() and decode_jwt_token().
 * Uses JJWT 0.12.x API (fluent builder pattern).
 */
@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long expirationHours;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-hours}") long expirationHours
    ) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("JWT_SECRET must be at least 32 characters long");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationHours = expirationHours;
    }

    /** Create a signed JWT token (mirrors Python create_jwt_token) */
    public String createToken(String userId, String email, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationHours * 3600_000L);

        return Jwts.builder()
                .claim("user_id", userId)
                .claim("email", email)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /** Decode and verify a JWT token. Throws 401 on failure (mirrors Python decode_jwt_token) */
    public Map<String, Object> decodeToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Map<String, Object> result = new HashMap<>();
            result.put("user_id", claims.get("user_id", String.class));
            result.put("email",   claims.get("email",   String.class));
            result.put("role",    claims.get("role",    String.class));
            return result;

        } catch (ExpiredJwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token has expired");
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
        }
    }
}
