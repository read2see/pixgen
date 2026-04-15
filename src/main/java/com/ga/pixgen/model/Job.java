package com.ga.pixgen.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
@ToString
@Entity
@Table(
        name = "jobs",
        indexes = {
                @Index(name = "ix_jobs_status_created_at", columnList = "status,created_at"),
                @Index(name = "ix_jobs_user_id_status", columnList = "user_id,status")
        }
)
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "negative_prompt", columnDefinition = "TEXT")
    private String negativePrompt;

    @Column
    private Integer width;

    @Column
    private Integer height;

    /** docker-diffusers-api {@code modelInputs.num_inference_steps} */
    @Column(name = "num_inference_steps")
    private Integer numInferenceSteps;

    /** docker-diffusers-api {@code modelInputs.guidance_scale} */
    @Column(name = "guidance_scale")
    private Double guidanceScale;

    @Column
    private Long seed;

    @Column(length = 64)
    private String sampler;

    /** docker-diffusers-api {@code callInputs.MODEL_ID} (HuggingFace id or local bundle id) */
    @Column(name = "model_id", length = 256)
    private String modelId;

    @Column(name = "credits_cost", nullable = false)
    private Integer creditsCost;

    @Column(nullable = false)
    private Integer progress;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "claimed_by_instance", length = 64)
    private String claimedByInstance;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = JobStatus.PENDING;
        }
        if (this.progress == null) {
            this.progress = 0;
        }
        if (this.creditsCost == null) {
            this.creditsCost = 0;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
