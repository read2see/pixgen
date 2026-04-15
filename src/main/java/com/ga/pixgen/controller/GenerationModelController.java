package com.ga.pixgen.controller;

import com.ga.pixgen.dto.GenerationModelOptionResponse;
import com.ga.pixgen.service.generation.GenerationModelCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes the catalog of locally available diffusion {@code model_id} values
 * (docker-diffusers-api {@code MODEL_ID}) and human-readable labels.
 */
@RestController
@RequestMapping("/api/generation")
@RequiredArgsConstructor
public class GenerationModelController {

    private final GenerationModelCatalog catalog;

    @GetMapping("/models")
    @PreAuthorize("hasAuthority('job.create')")
    public List<GenerationModelOptionResponse> listModels() {
        return catalog.listOptions();
    }
}
