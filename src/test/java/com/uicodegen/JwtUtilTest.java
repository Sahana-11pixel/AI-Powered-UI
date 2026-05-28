package com.uicodegen;

import com.uicodegen.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;
    private static final String SECRET = "test-secret-key-that-is-at-least-32-characters-long";

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 168);
    }

    @Test
    void createAndDecodeToken_roundTrip() {
        String token = jwtUtil.createToken("user-123", "test@example.com", "user");
        assertNotNull(token);

        Map<String, Object> claims = jwtUtil.decodeToken(token);
        assertEquals("user-123",        claims.get("user_id"));
        assertEquals("test@example.com", claims.get("email"));
        assertEquals("user",            claims.get("role"));
    }

    @Test
    void decodeToken_invalidToken_throws401() {
        assertThrows(Exception.class, () -> jwtUtil.decodeToken("invalid.token.here"));
    }

    @Test
    void decodeToken_tamperedToken_throws401() {
        String token = jwtUtil.createToken("user-123", "test@example.com", "user");
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";
        assertThrows(Exception.class, () -> jwtUtil.decodeToken(tampered));
    }

    @Test
    void constructor_shortSecret_throwsException() {
        assertThrows(IllegalArgumentException.class,
            () -> new JwtUtil("short", 168));
    }
}
