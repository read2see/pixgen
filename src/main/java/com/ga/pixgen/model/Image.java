package com.ga.pixgen.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
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
@ToString(exclude = "job")
@Entity
@Table(
        name = "images",
        indexes = {
                @Index(name = "ix_images_user_id_created_at", columnList = "user_id,created_at")
        }
)
public class Image {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", unique = true)
    private Job job;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "negative_prompt", columnDefinition = "TEXT")
    private String negativePrompt;

    @Column(name = "file_path", nullable = false, length = 512)
    private String filePath;

    @Column(name = "mime_type", length = 64)
    private String mimeType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(name = "model_id", length = 256)
    private String modelId;

    @Column(length = 64)
    private String sampler;

    @Column(name = "num_inference_steps")
    private Integer numInferenceSteps;

    @Column(name = "guidance_scale")
    private Double guidanceScale;

    @Column
    private Long seed;

    @Column(length = 64)
    private String scheduler;

    @Column(name = "clip_skip")
    private Integer clipSkip;

    @Column(name = "loras_json", columnDefinition = "TEXT")
    private String lorasJson;

    @Column(name = "extras_json", columnDefinition = "TEXT")
    private String extrasJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
