package com.uicodegen.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Initializes Firebase Admin SDK from a JSON credentials FILE.
 * Much cleaner than pasting huge JSON into .env (which causes shell parsing errors).
 *
 * Usage: Place your firebase-service-account.json in the project root,
 *        or set FIREBASE_CREDENTIALS_FILE env var to the file path.
 */
@Configuration
public class FirebaseConfig {

    @Value("${firebase.credentials-file}")
    private String credentialsFilePath;

    @PostConstruct
    public void initialize() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                InputStream serviceAccount = new FileInputStream(credentialsFilePath);

                FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();

                FirebaseApp.initializeApp(options);
                System.out.println("Firebase Admin SDK initialized from: " + credentialsFilePath);
            }
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to initialize Firebase! Make sure '" + credentialsFilePath +
                "' exists. Download it from Firebase Console → Project Settings → Service Accounts → Generate New Private Key",
                e
            );
        }
    }
}
