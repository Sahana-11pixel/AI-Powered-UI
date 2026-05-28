# UI Code Generator — Java Spring Boot Backend

A complete Java/Spring Boot reimplementation of the [ui5_full Python/FastAPI backend](../ui5_full/backend/), built for Java job applications.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Framework | Spring Boot 3.3 |
| REST API | Spring Web MVC |
| Security | Spring Security 6 + JJWT |
| Database | Spring Data MongoDB |
| Firebase Auth | Firebase Admin SDK for Java |
| AI Integration | Google Gemini via OkHttp REST |
| Image Storage | Cloudinary Java SDK |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Build Tool | Maven 3.9 |
| Testing | JUnit 5 + Mockito |
| Container | Docker + docker-compose |
| CI/CD | GitHub Actions |

## Project Structure

```
src/main/java/com/uicodegen/
├── config/          # Security, CORS, Firebase, Cloudinary setup
├── security/        # JWT util, auth filter, user principal
├── model/           # MongoDB documents (User, Project, ApiUsage)
├── dto/             # Request/response DTOs (mirrors Python Pydantic models)
├── repository/      # Spring Data MongoDB repositories
├── service/         # Business logic (Auth, Gemini AI, CodeSafety, Project, Admin)
├── controller/      # REST controllers (Auth, Upload, Generate, Chat, Project, Admin)
└── exception/       # Global exception handler
```

## API Endpoints (identical to Python backend)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/` | Public | Health check |
| POST | `/api/auth/signup` | Public | Register user |
| POST | `/api/auth/login` | Public | Login |
| POST | `/api/auth/firebase-login` | Public | Firebase OAuth login |
| GET | `/api/auth/me` | JWT | Get current user |
| PUT | `/api/auth/profile` | JWT | Update display name |
| POST | `/api/auth/reset-password` | JWT | Change password |
| DELETE | `/api/auth/account` | JWT | Soft-delete account |
| POST | `/api/upload` | JWT | Upload UI screenshot |
| POST | `/api/generate` | JWT | Generate code with Gemini AI |
| POST | `/api/chat` | JWT | Chat to refine code |
| GET | `/api/projects` | JWT | List user projects |
| POST | `/api/projects` | JWT | Save project |
| GET | `/api/projects/{id}` | JWT | Get project |
| PUT | `/api/projects/{id}` | JWT | Update project |
| DELETE | `/api/projects/{id}` | JWT | Delete project |
| GET | `/api/admin/stats` | ADMIN | Dashboard stats |
| GET | `/api/admin/users` | ADMIN | Paginated users |
| GET | `/api/admin/users/{id}` | ADMIN | User details |
| PUT | `/api/admin/users/{id}/role` | ADMIN | Update role |
| DELETE | `/api/admin/users/{id}` | ADMIN | Soft-delete user |
| GET | `/api/admin/projects` | ADMIN | Paginated projects |
| DELETE | `/api/admin/projects/{id}` | ADMIN | Delete project |

## Setup

### 1. Prerequisites
- Java 21+
- Maven 3.9+
- MongoDB (local or Atlas)
- Firebase project with service account
- Gemini API key
- Cloudinary account

### 2. Configure environment

```bash
cp .env.example .env
# Fill in all values in .env
```

### 3. Run locally

```bash
# Set env vars (Windows PowerShell)
$env:MONGO_URI="your_uri"
$env:JWT_SECRET="your_secret"
# ... etc

mvn spring-boot:run
```

Then open: http://localhost:8080/swagger-ui.html

### 4. Run with Docker

```bash
cp .env.example .env
# Fill in .env
docker-compose up --build
```

### 5. Run tests

```bash
mvn test
```

## CI/CD Pipeline

GitHub Actions workflow at `.github/workflows/ci-cd.yml`:

1. **Build & Test** — runs on every push/PR
2. **Docker Build & Push** — pushes to Docker Hub on `main`
3. **Deploy** — triggers Render deploy hook (optional)

### Required GitHub Secrets

| Secret | Description |
|--------|-------------|
| `DOCKERHUB_USERNAME` | Docker Hub username |
| `DOCKERHUB_TOKEN` | Docker Hub access token |
| `RENDER_DEPLOY_HOOK` | Render deploy webhook URL (optional) |

## Key Design Decisions

- **Sync MongoDB** (not reactive) — easier to understand and explain in interviews
- **OkHttp for Gemini** — lightweight REST calls instead of heavy Google SDK
- **Same API contracts** — React frontend works with zero changes, just swap the backend URL
- **Identical error responses** — `{"detail": "..."}` format matches Python `HTTPException`
- **Same JWT structure** — `user_id`, `email`, `role` claims match the Python backend
