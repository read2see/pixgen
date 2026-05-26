# PixGen

PixGen is a Spring Boot REST API for an AI-powered image generation platform. It handles account management, HTTP-only cookie authentication, role-based access control, asynchronous image generation jobs, local image storage, community posts, comments, and admin/moderator workflows.

The API is designed to sit between a front end and an optional internal image generation service. Front-end clients call PixGen only; PixGen either runs a simulated in-process generator or delegates generation work to the configured internal service.

## Features

- User registration, login, logout, email verification, password reset, and password change
- HTTP-only JWT cookie sessions with Spring Security authorization
- Role and permission based access for users, moderators, and admins
- Credit-based image generation requests
- Asynchronous job queue with progress tracking, cancellation, and Server-Sent Events
- Configurable generation model catalog
- Local generated image and profile image storage
- Public post feed with image attachments
- Threaded comments with moderation support
- Admin dashboards for users, jobs, posts, comments, and images
- PostgreSQL persistence with JPA repositories
- Test coverage for controllers, services, repositories, security, jobs, and storage

## Tech Stack

- Java 17
- Spring Boot 4
- Spring MVC
- Spring Security
- Spring Data JPA
- PostgreSQL
- Thymeleaf email templates
- Maven Wrapper
- Testcontainers
- JJWT
- Lombok

## Project Structure

```text
src/main/java/com/ga/pixgen
|-- config/          Application, security, job, seed, and storage configuration
|-- controller/      REST controllers
|-- dto/             Request and response DTOs
|-- event/           Email events and listeners
|-- exception/       Domain exceptions and global error handling
|-- model/           JPA entities and enums
|-- repository/      Spring Data repositories
|-- security/        JWT, cookies, filters, and user details
`-- service/         Auth, jobs, images, posts, comments, credits, and profile logic

drafts/
|-- api-spec/        Endpoint reference and Postman collection
`-- front-end-api-reference.json

diagrams/
|-- job-sequence-diagram.md
`-- erd/
```

## Prerequisites

- Java 17
- Maven 3.9+ or the included Maven Wrapper
- PostgreSQL 14+
- Optional: MailHog or another local SMTP server for verification and password reset emails
- Optional: an internal image generation service compatible with the contract referenced in `drafts/front-end-api-reference.json`

## Installation

1. Clone the repository and enter the project directory.

   ```bash
   git clone <repository-url>
   cd pixgen
   ```

2. Create a PostgreSQL database.

   ```bash
   createdb pixgen
   ```

3. Review the local development profile in `src/main/resources/application-dev.properties`.

   ```properties
   server.port=8888
   spring.datasource.url=jdbc:postgresql://localhost:5432/pixgen
   spring.datasource.username=postgres
   spring.datasource.password=12345678
   app.backend.base-url=http://localhost:8888
   app.frontend.base-url=localhost:3000
   ```

4. Choose the image generation backend.

   ```properties
   # true: use the in-process simulated generator
   # false: call the configured internal image service
   app.internal-service-simulation=true
   app.internal-service.base-url=http://localhost:8000
   ```

5. Start the API.

   ```bash
   ./mvnw spring-boot:run
   ```

   On Windows:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

The API runs at `http://localhost:8888` by default.

## Default Development Account

The development seed creates an admin account from these properties:

```properties
seed.admin.email=admin@pixgen.local
seed.admin.password=Admin@12345
```

Update these values before using the application outside a local development environment.

## Configuration Notes

- `spring.profiles.active=dev` is set in `src/main/resources/application.properties`.
- `spring.jackson.property-naming-strategy=SNAKE_CASE` makes JSON fields snake_case at the API boundary.
- `app.cookie.secure=false` is intended for local HTTP development.
- `app.jobs.max-jobs-per-instance`, `app.jobs.max-active-jobs-per-user`, and related job properties control queue throughput and per-user limits.
- `app.images.local-dir` and `app.profile-images.local-dir` control local file storage.
- `app.generation.models[*]` defines the public generation model catalog.

## Usage

1. Register a user with `POST /api/auth/register`.
2. Verify the account using the verification email flow, or use the seeded admin account in development.
3. Log in with `POST /api/auth/login`; the API sets an HTTP-only JWT cookie.
4. Fetch available generation models with `GET /api/generation/models`.
5. Create a generation job with `POST /api/jobs`.
6. Watch job progress through `GET /api/jobs/stream` or `GET /api/jobs/{id}/stream`.
7. After success, retrieve image metadata from `GET /api/images/{id}` and the binary file from `GET /api/images/{id}/file`.
8. Publish generated images to the community feed with `POST /api/posts`.

Clients should send `credentials: include` for authenticated requests and should not store JWTs in browser storage.

## API Specification

Base URL in development:

```text
http://localhost:8888
```

Base path:

```text
/api
```

Authentication uses HTTP-only cookie sessions. Public routes do not require a session; private routes require an authenticated user and, in some cases, a specific role or permission.

### Response Conventions

- Timestamps are ISO-8601 strings.
- Paginated endpoints return Spring-style page responses with `content`, `totalElements`, `totalPages`, `size`, `number`, `first`, `last`, and related fields.
- Error responses include `timestamp`, `status`, `error`, `message`, `path`, and optional `fieldErrors`.
- Common handled statuses include `400`, `401`, `403`, `404`, `409`, `422`, and `500`.

### Authentication

| Request Type | URL | Functionality | Access |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | Register a new user account and issue an email verification token. | Public |
| POST | `/api/auth/send-verification` | Issue or resend an email verification token for the supplied email address. | Public |
| GET | `/api/auth/verify-email` | Verify an email address by token and redirect to the front-end dashboard. | Public |
| POST | `/api/auth/forgot-password` | Request a password reset token for the supplied email address. | Public |
| GET | `/api/auth/reset-password` | Validate a password reset token and redirect to the front-end reset-password page. | Public |
| POST | `/api/auth/reset-password` | Reset a password using a valid reset token. | Public |
| POST | `/api/auth/change-password` | Change the authenticated user's password. | Private |
| POST | `/api/auth/login` | Authenticate a user and set the HTTP-only JWT cookie. | Public |
| POST | `/api/auth/logout` | Clear the HTTP-only JWT cookie. | Public |
| GET | `/api/auth/me` | Return the authenticated user's full profile. | Private |

### Users

| Request Type | URL | Functionality | Access |
| --- | --- | --- | --- |
| GET | `/api/users/me` | Return the authenticated principal name. | Private |
| GET | `/api/users/me/stats` | Return the authenticated user's usage statistics, including active jobs, generated images, and credits. | Private |
| POST | `/api/users/me/profile-image` | Upload and replace the authenticated user's profile image using multipart form data. | Private |
| GET | `/api/users/me/profile-image` | Return the authenticated user's profile image file. | Private |
| POST | `/api/users/{id}/credits/increase` | Increase a user's credit balance; requires the `credits.grant` authority. | Private |

### Generation

| Request Type | URL | Functionality | Access |
| --- | --- | --- | --- |
| GET | `/api/generation/models` | List available image generation model options; requires the `job.create` authority. | Private |

### Jobs

| Request Type | URL | Functionality | Access |
| --- | --- | --- | --- |
| POST | `/api/jobs` | Create and enqueue an image generation job for the authenticated user. | Private |
| GET | `/api/jobs/{id}` | Return a specific job owned by, or otherwise visible to, the authenticated user. | Private |
| GET | `/api/jobs/me` | List the authenticated user's jobs, optionally filtered by status. | Private |
| POST | `/api/jobs/{id}/cancel` | Request cancellation of a job visible to the authenticated user. | Private |
| GET | `/api/jobs/stream` | Open a server-sent events stream for the authenticated user's job updates. | Private |
| GET | `/api/jobs/{id}/stream` | Open a server-sent events stream for one job visible to the authenticated user. | Private |

### Images

| Request Type | URL | Functionality | Access |
| --- | --- | --- | --- |
| GET | `/api/images/me` | List images owned by the authenticated user. | Private |
| GET | `/api/images/{id}` | Return metadata for an image visible to the authenticated user. | Private |
| GET | `/api/images/{id}/file` | Return the image file for an image visible to the authenticated user. | Private |

### Posts

| Request Type | URL | Functionality | Access |
| --- | --- | --- | --- |
| POST | `/api/posts` | Create a post for the authenticated user; requires the `post.create` authority. | Private |
| GET | `/api/posts` | Return the public post feed with pageable sorting. | Public |
| GET | `/api/posts/{id}` | Return a public post by ID. | Public |

### Comments

| Request Type | URL | Functionality | Access |
| --- | --- | --- | --- |
| POST | `/api/posts/{postId}/comments` | Create a comment on a post for the authenticated user; requires the `comment.create` authority. | Private |
| GET | `/api/posts/{postId}/comments` | List public comments for a post in threaded order. | Public |

### Admin

| Request Type | URL | Functionality | Access |
| --- | --- | --- | --- |
| GET | `/api/admin/ping` | Return an admin-only health check response. | Private |
| GET | `/api/admin/users` | List users with optional query, role, enabled, deleted, and pageable filters. | Private |
| PATCH | `/api/admin/users/{id}/suspend` | Suspend a user; requires the `user.delete` authority. | Private |
| DELETE | `/api/admin/users/{id}` | Soft-delete a user; requires the `user.delete` authority. | Private |
| PATCH | `/api/admin/users/{id}/role` | Change a user's role; requires the `role.manage` authority. | Private |
| GET | `/api/admin/jobs` | List jobs with optional status, user ID, date range, and pageable filters. | Private |
| GET | `/api/admin/posts` | List posts with optional status, visibility, author username, and pageable filters. | Private |
| GET | `/api/admin/comments` | List comments with optional status, author username, post ID, and pageable filters. | Private |
| GET | `/api/admin/images` | List images with optional user ID, job ID, and pageable filters. | Private |

### Moderator

| Request Type | URL | Functionality | Access |
| --- | --- | --- | --- |
| GET | `/api/moderator/posts` | List posts for moderation with optional status and pageable filters. | Private |
| GET | `/api/moderator/comments` | List comments for moderation with optional status and pageable filters. | Private |
| GET | `/api/moderator/images` | List images for moderation with pageable filters. | Private |
| POST | `/api/moderator/posts/{id}/hide` | Hide a post as a moderator or admin. | Private |
| POST | `/api/moderator/comments/{id}/hide` | Hide a comment as a moderator or admin. | Private |

Full draft API resources:

- `drafts/api-spec/api-endpoints.md`
- `drafts/api-spec/pixgen.postman_collection.json`
- `drafts/front-end-api-reference.json`

## Job Architecture

Image generation is submitted as a job so HTTP requests stay short while generation runs asynchronously in the worker pool. The scheduler claims pending jobs with database locking, the worker streams progress through the in-process SSE broker, and successful completion persists both the generated image and final job status.

See the [job sequence diagram](diagrams/job-sequence-diagram.md) for the request, worker, and event flow.

## Database Design

The project ERDs are available in the `diagrams/erd` directory:

- [Initial ERD](diagrams/erd/initial-erd.md)
- [Final ERD](diagrams/erd/final-erd.md)

## Testing

Run the full test suite:

```bash
./mvnw test
```

On Windows:

```powershell
.\mvnw.cmd test
```

Repository tests cover authentication, authorization, RBAC, controllers, services, repositories, jobs, image generation strategies, storage, email events, and exception handling.

## Related Repositories

- `pixgen-front-end`
- `pixgen-internal-service`

## Project Management

- [Week #1 Trello board](https://trello.com/b/q33CT7qR/ga-project-3-week-1-tasks)
