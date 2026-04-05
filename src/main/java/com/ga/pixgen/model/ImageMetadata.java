package com.ga.pixgen.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
@ToString(exclude = "image")
@Entity
@Table(name = "image_metadata")
public class ImageMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "image_id", unique = true, nullable = false)
    private Image image;

    @Column(name = "model_name")
    private String modelName;

    @Column
    private String sampler;

    @Column
    private Integer steps;

    @Column(name = "cfg_scale")
    private Double cfgScale;

    @Column
    private Long seed;

    @Column
    private String scheduler;

    @Column(name = "clip_skip")
    private Integer clipSkip;

    @Column(name = "loras_json", columnDefinition = "TEXT")
    private String lorasJson;

    @Column(name = "extras_json", columnDefinition = "TEXT")
    private String extrasJson;
}
