package com.pilot.server.session.question;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.tool.QuestionBroker;

/** 点选或自行填写后，把答案交回正在等待的任务。 */
@RestController
public class QuestionController {
    private final QuestionBroker questions;

    public QuestionController(QuestionBroker questions) {
        this.questions = questions;
    }

    @GetMapping("/api/questions")
    public List<QuestionBroker.PendingQuestion> pending() {
        return questions.pending();
    }

    @PostMapping("/api/sessions/{id}/questions/{callId}")
    public Map<String, String> answer(@PathVariable String id, @PathVariable String callId,
        @RequestBody Map<String, String> body) {
        String choice = body == null ? "" : body.getOrDefault("choice", "");
        if (!questions.answer(id, callId, choice)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这个问题已经不在等待或不属于当前对话");
        }
        return Map.of("choice", choice.strip());
    }
}
