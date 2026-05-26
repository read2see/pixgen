## API Endpoints

### Authentication
|Request Type|URL|Functionality|Access|
|---|---|---|---|
|POST|/api/auth/register|Register a new user account and issue an email verification token.|Public|
|POST|/api/auth/send-verification|Issue or resend an email verification token for the supplied email address.|Public|
|GET|/api/auth/verify-email|Verify an email address by token and redirect to the front-end dashboard.|Public|
|POST|/api/auth/forgot-password|Request a password reset token for the supplied email address.|Public|
|GET|/api/auth/reset-password|Validate a password reset token and redirect to the front-end reset-password page.|Public|
|POST|/api/auth/reset-password|Reset a password using a valid reset token.|Public|
|POST|/api/auth/change-password|Change the authenticated user's password.|Private|
|POST|/api/auth/login|Authenticate a user and set the HTTP-only JWT cookie.|Public|
|POST|/api/auth/logout|Clear the HTTP-only JWT cookie.|Public|
|GET|/api/auth/me|Return the authenticated user's full profile.|Private|

### Users
|Request Type|URL|Functionality|Access|
|---|---|---|---|
|GET|/api/users/me|Return the authenticated principal name.|Private|
|GET|/api/users/me/stats|Return the authenticated user's usage statistics, including active jobs, generated images, and credits.|Private|
|POST|/api/users/me/profile-image|Upload and replace the authenticated user's profile image using multipart form data.|Private|
|GET|/api/users/me/profile-image|Return the authenticated user's profile image file.|Private|
|POST|/api/users/{id}/credits/increase|Increase a user's credit balance; requires the credits.grant authority.|Private|

### Generation
|Request Type|URL|Functionality|Access|
|---|---|---|---|
|GET|/api/generation/models|List available image generation model options; requires the job.create authority.|Private|

### Jobs
|Request Type|URL|Functionality|Access|
|---|---|---|---|
|POST|/api/jobs|Create and enqueue an image generation job for the authenticated user.|Private|
|GET|/api/jobs/{id}|Return a specific job owned by, or otherwise visible to, the authenticated user.|Private|
|GET|/api/jobs/me|List the authenticated user's jobs, optionally filtered by status.|Private|
|POST|/api/jobs/{id}/cancel|Request cancellation of a job visible to the authenticated user.|Private|
|GET|/api/jobs/stream|Open a server-sent events stream for the authenticated user's job updates.|Private|
|GET|/api/jobs/{id}/stream|Open a server-sent events stream for one job visible to the authenticated user.|Private|

### Images
|Request Type|URL|Functionality|Access|
|---|---|---|---|
|GET|/api/images/me|List images owned by the authenticated user.|Private|
|GET|/api/images/{id}|Return metadata for an image visible to the authenticated user.|Private|
|GET|/api/images/{id}/file|Return the image file for an image visible to the authenticated user.|Private|

### Posts
|Request Type|URL|Functionality|Access|
|---|---|---|---|
|POST|/api/posts|Create a post for the authenticated user; requires the post.create authority.|Private|
|GET|/api/posts|Return the public post feed with pageable sorting.|Public|
|GET|/api/posts/{id}|Return a public post by ID.|Public|

### Comments
|Request Type|URL|Functionality|Access|
|---|---|---|---|
|POST|/api/posts/{postId}/comments|Create a comment on a post for the authenticated user; requires the comment.create authority.|Private|
|GET|/api/posts/{postId}/comments|List public comments for a post in threaded order.|Public|

### Admin
|Request Type|URL|Functionality|Access|
|---|---|---|---|
|GET|/api/admin/ping|Return an admin-only health check response.|Private|
|GET|/api/admin/users|List users with optional query, role, enabled, deleted, and pageable filters.|Private|
|PATCH|/api/admin/users/{id}/suspend|Suspend a user; requires the user.delete authority.|Private|
|DELETE|/api/admin/users/{id}|Soft-delete a user; requires the user.delete authority.|Private|
|PATCH|/api/admin/users/{id}/role|Change a user's role; requires the role.manage authority.|Private|
|GET|/api/admin/jobs|List jobs with optional status, user ID, date range, and pageable filters.|Private|
|GET|/api/admin/posts|List posts with optional status, visibility, author username, and pageable filters.|Private|
|GET|/api/admin/comments|List comments with optional status, author username, post ID, and pageable filters.|Private|
|GET|/api/admin/images|List images with optional user ID, job ID, and pageable filters.|Private|

### Moderator
|Request Type|URL|Functionality|Access|
|---|---|---|---|
|GET|/api/moderator/posts|List posts for moderation with optional status and pageable filters.|Private|
|GET|/api/moderator/comments|List comments for moderation with optional status and pageable filters.|Private|
|GET|/api/moderator/images|List images for moderation with pageable filters.|Private|
|POST|/api/moderator/posts/{id}/hide|Hide a post as a moderator or admin.|Private|
|POST|/api/moderator/comments/{id}/hide|Hide a comment as a moderator or admin.|Private|
