package com.ga.pixgen.controller;

import com.ga.pixgen.dto.CreateJobRequest;
import com.ga.pixgen.dto.JobResponse;
import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import com.ga.pixgen.model.User;
import com.ga.pixgen.security.CustomUserDetails;
import com.ga.pixgen.service.jobs.JobEventBroker;
import com.ga.pixgen.service.jobs.JobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Public HTTP surface for {@link com.ga.pixgen.service.jobs.JobService}.
 *
 * <p>Each endpoint is a thin wrapper: the controller authenticates via the
 * standard JWT cookie filter, applies the {@code @PreAuthorize} permission
 * gate, and forwards to {@link JobService}. Ownership is enforced inside the
 * service so non-HTTP entry points cannot bypass it. The SSE endpoints
 * register {@link SseEmitter}s on the {@link JobEventBroker}; the
 * per-job stream first calls {@code JobService.get} to make ownership
 * mistakes visible as {@code 404}/{@code 403} instead of as silent
 * never-fired streams.</p>
 */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    private final JobEventBroker jobEventBroker;

    @PostMapping
    @PreAuthorize("hasAuthority('job.create')")
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse create(@AuthenticationPrincipal CustomUserDetails principal,
                              @Valid @RequestBody CreateJobRequest request) {
        User user = principal.getUser();
        Job job = jobService.submit(user, request.toSubmission());
        return JobResponse.fromEntity(job, jobService.queuePosition(job), null);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('job.read')")
    public JobResponse get(@PathVariable Long id,
                           @AuthenticationPrincipal CustomUserDetails principal) {
        Job job = jobService.get(id, principal.getUser());
        return JobResponse.fromEntity(job, jobService.queuePosition(job), null);
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('job.read')")
    public List<JobResponse> listMine(@AuthenticationPrincipal CustomUserDetails principal,
                                      @RequestParam(value = "status", required = false) JobStatus status) {
        return jobService.listMine(principal.getUser(), status).stream()
                .map(job -> JobResponse.fromEntity(job, jobService.queuePosition(job), null))
                .toList();
    }

    /**
     * Cancel a job. Authorisation is gated by the {@code job.cancel}
     * permission, which is wired onto the ADMIN, MODERATOR and USER roles
     * by {@link com.ga.pixgen.config.seed.RolePermissionSeeder}. Ownership
     * and state-transition rules are still enforced by the service so a
     * permitted caller cannot cancel another user's job (moderators are
     * the only role allowed to cross that boundary).
     *
     * @param id the id value
     * @param principal the principal value
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('job.cancel')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long id,
                       @AuthenticationPrincipal CustomUserDetails principal) {
        jobService.cancel(id, principal.getUser());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('job.read')")
    public SseEmitter streamAll(@AuthenticationPrincipal CustomUserDetails principal) {
        return jobEventBroker.register(principal.getUser().getId());
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('job.read')")
    public SseEmitter streamJob(@PathVariable Long id,
                                @AuthenticationPrincipal CustomUserDetails principal) {
        Job job = jobService.get(id, principal.getUser());
        return jobEventBroker.register(job.getUserId(), job.getId());
    }
}
