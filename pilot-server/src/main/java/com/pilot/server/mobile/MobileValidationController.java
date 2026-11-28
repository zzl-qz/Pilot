package com.pilot.server.mobile;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.server.session.lookup.SessionLookup;
import com.pilot.server.validation.ValidationSnapshot;
import com.pilot.server.validation.ValidationStatusService;

import lombok.RequiredArgsConstructor;

/**
 * 手机上看到的验证结论。
 *
 * <p>和桌面读的是同一本账、同一份指纹，所以三端能对同一件事给出同一个答案；
 * 但这里只给结论，不给文件名、工作区路径和命令正文——手机现在不需要那些，
 * 而路径本身就是一类不该随手带出本机的信息。
 */
@RestController
@RequestMapping("/api/mobile/sessions/{id}/validation")
@RequiredArgsConstructor
public class MobileValidationController {
    private final SessionService sessions;
    private final ValidationStatusService validations;

    @GetMapping
    public View validation(@PathVariable String id) {
        Session session = sessions.find(id).orElse(null);
        if (session == null) {
            return new View("unverified", "no_evidence", "任务不存在", "", "", "");
        }
        ValidationSnapshot snapshot = validations.snapshot(id, SessionLookup.workspaceOf(session), null);
        return new View(snapshot.status(), snapshot.code(), snapshot.reason(), snapshot.candidateHash(),
            snapshot.evidenceId(), snapshot.recordedAt().toString());
    }

    /**
     * @param candidateHash 完整指纹；界面自己截前 8 位与桌面核对
     * @param recordedAt    证据记录时间，没有证据时是纪元起点
     */
    public record View(String status, String code, String reason, String candidateHash, String evidenceId,
        String recordedAt) {
    }
}
