package com.pilot.server.model;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** 多模型配置预设管理接口：保存、编辑、删除与一键切换。 */
@RestController
@RequestMapping("/api/settings/model/profiles")
@RequiredArgsConstructor
public final class ModelProfilesController {
    private final ModelProfileStore profiles;

    @GetMapping
    public ModelProfileStore.ProfilesView list() {
        return profiles.list();
    }

    @PostMapping
    public ModelProfileStore.ProfilesView create(@RequestBody(required = false) ModelProfileStore.SaveRequest request) {
        try {
            return profiles.create(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ModelProfileStore.ProfilesView update(@PathVariable String id,
        @RequestBody(required = false) ModelProfileStore.SaveRequest request) {
        try {
            return profiles.update(id, request);
        } catch (ModelProfileStore.NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ModelProfileStore.ProfilesView delete(@PathVariable String id) {
        try {
            return profiles.delete(id);
        } catch (ModelProfileStore.NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/{id}/activate")
    public ModelProfileStore.ProfilesView activate(@PathVariable String id) {
        try {
            return profiles.activate(id);
        } catch (ModelProfileStore.NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping("/fallback")
    public ModelProfileStore.ProfilesView setFallback(@RequestBody(required = false) FallbackRequest request) {
        try {
            return profiles.setFallback(request == null ? "" : request.profileId());
        } catch (ModelProfileStore.NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    public record FallbackRequest(String profileId) {
    }
}
