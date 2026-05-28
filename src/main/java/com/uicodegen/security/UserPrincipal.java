package com.uicodegen.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Custom principal stored in the SecurityContext.
 * Controllers inject this via @AuthenticationPrincipal UserPrincipal.
 */
@Getter
@AllArgsConstructor
public class UserPrincipal {
    private final String userId;
    private final String email;
    private final String role;
}
