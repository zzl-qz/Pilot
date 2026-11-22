package com.pilot.server.completion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.session.lookup.SessionLookup;

import lombok.RequiredArgsConstructor;

/**
 * 完成凭证接口。
 *
 * <p>{@code GET /completion} 返回机器可读包，{@code GET /completion/markdown} 返回可粘贴的交付凭证；
 * {@code POST /completion/verify} 只核对现有证据与当前文件，不会偷偷重跑命令。
 */
@RestController
@RequestMapping("/api/sessions/{id}/completion")
@RequiredArgsConstructor
public class CompletionController {
    private final SessionService sessions;
    private final CompletionBundleService completion;

    @GetMapping
    public CompletionBundleService.CompletionBundle bundle(@PathVariable String id) {
        require(id);
        return completion.build(id);
    }

    @GetMapping(value = "/markdown", produces = "text/markdown;charset=UTF-8")
    public String markdown(@PathVariable String id) {
        require(id);
        return completion.markdown(completion.build(id));
    }

    @PostMapping("/verify")
    public CompletionBundleService.VerifyReport verify(@PathVariable String id) {
        require(id);
        return completion.verify(id);
    }

    private Session require(String id) {
        return SessionLookup.require(sessions, id);
    }
}
