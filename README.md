# UI Code Generator — Java Spring Boot Backend

> **Note:** This repository contains the **Backend** code for the UI Code Generator project. 
> For the **Frontend** React application, please visit: https://github.com/Sahana-11pixel/AI-Powered-UI-Frontend-.git

*This backend powers a full-stack web application that allows users to upload a screenshot and generate frontend code using their preferred framework. It also provides an AI assistant for chatting, explaining, and modifying the generated code.
*---

## 🚀 Features (Supported by this Backend)

- **AI Code Generation** — Processes UI screenshots and uses Google Gemini AI to generate frontend code.
- **AI Assistant Chat** — Chat endpoints to communicate with Google Gemini AI for code help, explanations, and improvements.
- **Project Management** — RESTful APIs to save, load, and manage user projects in MongoDB.
- **User Authentication** — Secure authentication using Firebase Admin SDK and Spring Security with JWT.
- **Image Processing & Storage** — Handles image uploads and stores them securely via Cloudinary Java SDK.
- **Admin Dashboard** — Endpoints for administrators to manage users, roles, and monitor system statistics.
- **API Documentation** — Auto-generated OpenAPI specs via SpringDoc (Swagger UI).

---

## 🧱 Tech Stack

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

---

## 📁 Project Structure

```text
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

---

## 🔌 API Endpoints

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

---

## ⚙️ Setup & Local Development

### 1. Prerequisites
- Java 21+
- Maven 3.9+
- MongoDB (local or Atlas)
- Firebase project with service account
- Gemini API key
- Cloudinary account

### 2. Configure Environment

```bash
cp .env.example .env
# Fill in all required environment variables in .env
```

### 3. Run Locally

```bash
# Set env vars (Windows PowerShell example)
$env:MONGO_URI="your_uri"
$env:JWT_SECRET="your_secret"
# ... etc

mvn spring-boot:run
```

Then open the Swagger UI: `http://localhost:8080/swagger-ui.html`

### 4. Run with Docker

```bash
cp .env.example .env
# Fill in .env
docker-compose up --build
```

### 5. Run Tests

```bash
mvn test
```

---

## 🚀 CI/CD Pipeline

GitHub Actions workflow is located at `.github/workflows/ci-cd.yml`:

1. **Build & Test** — runs on every push/PR.
2. **Docker Build & Push** — pushes to Docker Hub on `main` branch.
3. **Deploy** — triggers Render deploy hook (optional).

### Required GitHub Secrets

| Secret | Description |
|--------|-------------|
| `DOCKERHUB_USERNAME` | Docker Hub username |
| `DOCKERHUB_TOKEN` | Docker Hub access token |
| `RENDER_DEPLOY_HOOK` | Render deploy webhook URL (optional) |

---

