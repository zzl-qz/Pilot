package com.pilot.server.model;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/settings/model/runtime")
public final class ModelRuntimeController {
    private final ModelRuntimeSettings settings;

    public ModelRuntimeController(ModelRuntimeSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    public ModelRuntimeSettings.View current() {
        return settings.current();
    }

    public record Update(String model, String endpoint, ModelRuntimeSettings.Options options) {
    }

    @PutMapping
    public ModelRuntimeSettings.View update(@RequestBody Update value) {
        try {
            return settings.update(value.model(), value.endpoint(), value.options());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
