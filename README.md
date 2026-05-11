# PixGen
PixGen is a Spring Boot-based REST API that provides an AI-powered image generation platform built around asynchronous job processing and Java concurrency.

---

## Installation

### Prerequisites

- Java 17
- Maven 3.9+
- PostgreSQL 14+
- Optional: MailHog or another local SMTP server for verification and password reset email testing
- Optional: an internal image generation service compatible with the API documented in `drafts/internal-service-api-reference.json`

### Local Setup

1. Create a PostgreSQL database:

   ```bash
   createdb pixgen
   ```

2. Configure the local profile in `src/main/resources/application-dev.properties`:

   ```properties
   spring.datasource.url=jdbc:postgresql://localhost:5432/pixgen
   spring.datasource.username=postgres
   spring.datasource.password=12345678
   app.backend.base-url=http://localhost:8888
   app.frontend.base-url=localhost:3000
   ```

3. Choose the generation backend:

   ```properties
   # true: use the in-process simulated generator
   # false: call the configured internal image service
   app.internal-service-simulation=true
   app.internal-service.base-url=http://localhost:8000
   ```

4. Start the API:

   ```bash
   mvn spring-boot:run
   ```

5. Run tests:

   ```bash
   mvn test
   ```

The API runs on `http://localhost:8888` by default. The development seed creates the admin account configured by `seed.admin.email` and `seed.admin.password`.

## Job Architecture

Image generation is submitted as a job so HTTP requests stay short while generation runs asynchronously in the worker pool. The scheduler claims pending jobs with database locking, the worker streams progress through the in-process SSE broker, and successful completion persists both the generated image and the final job status in one transactional step.

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant JobController
    participant JobService
    participant JobRepository
    participant JobScheduler
    participant ActiveJobRegistry
    participant JobWorker
    participant ImageGenerator
    participant JobCompletionService
    participant ImageRepository
    participant JobEventBroker
    participant SSE as SSE Client

    Client->>JobController: POST /api/jobs
    JobController->>JobService: submit(user, request)
    JobService->>JobRepository: count pending jobs
    JobService->>JobService: validate credits and model
    JobService->>JobRepository: save PENDING job
    JobController-->>Client: 201 JobResponse

    Client->>JobController: GET /api/jobs/{id}/stream
    JobController->>JobEventBroker: register(userId, jobId)
    JobEventBroker-->>SSE: HEARTBEAT + current snapshot

    loop Scheduled poll
        JobScheduler->>JobRepository: claimNextPending(... FOR UPDATE SKIP LOCKED)
        JobScheduler->>ActiveJobRegistry: tryRegister(jobId, userId)
        JobScheduler->>JobRepository: mark RUNNING
        JobScheduler->>JobEventBroker: publish RUNNING
        JobScheduler->>JobWorker: execute(job)
    end

    JobWorker->>ImageGenerator: generate(request, progressListener)
    loop Progress updates
        ImageGenerator-->>JobWorker: progress(percent)
        JobWorker->>JobRepository: updateProgress(jobId, percent)
        JobWorker->>JobEventBroker: publish PROGRESS
        JobEventBroker-->>SSE: event: PROGRESS
    end

    alt Generation succeeds
        JobWorker->>JobCompletionService: completeSuccess(job, storedImage)
        JobCompletionService->>JobRepository: deduct credits if cost > 0
        JobCompletionService->>ImageRepository: save image metadata
        JobCompletionService->>JobRepository: mark SUCCEEDED
        JobWorker->>JobEventBroker: publish SUCCEEDED
        JobEventBroker-->>SSE: event: STATUS SUCCEEDED
    else Cancelled or failed
        JobWorker->>JobRepository: mark CANCELLED or FAILED
        JobWorker->>JobEventBroker: publish terminal status
        JobEventBroker-->>SSE: event: STATUS
    end

    JobWorker->>ActiveJobRegistry: release(jobId)
```

## Trello Boards
- [Week #1](https://trello.com/b/q33CT7qR/ga-project-3-week-1-tasks)

## Initial ERD

```mermaid
erDiagram

	Roles ||--o{ Users : "has"  
	Role_Permissions }o--|| Roles : "belongs to"  
	Role_Permissions }o--|| Permissions : "maps"    
	Users ||--o{ Jobs : "creates"  
	Users ||--o{ User_Generated_Images : "owns"  
	Users ||--o{ Posts : "creates"  
	Users ||--o{ Comments : "writes"  
	Jobs ||--o| User_Generated_Images : "produces"  
	Posts ||--o{ Comments : "has"  
	User_Generated_Images ||--o{ Comments : "has"  
	Comments ||--o{ Comments : "replies to"

Users {
	bigint id PK
	varchar username
	varchar password
	int credits
	bigint role_id FK
}

Jobs {
 bigint id PK
 bigint user_id FK
 varchar status
 type other relevant fields
}

User_Generated_Images {
	bigint id PK
	bigint user_id FK
	varchar prompt
	type other relevant fields
}

Posts {
	bigint id PK
	bigint user_id FK
	varchar title
}

Comments {
	bigint id PK
	bigint commentable_id PK,FK
	bigint commentable_type PK
	bigint parent_id FK
	bigint author_id FK
	varchar content
	text path
}

Tokens {
	uuid token PK
	varchar email
	varchar type
	type other relevant fields
}

Roles {
	bigint id PK
	varchar name
}

Permissions {
	bigint id PK
	varchar permission
}

Role_Permissions {
	bigint role_id PK,FK
	bigint permission_id PK,FK	
}
```