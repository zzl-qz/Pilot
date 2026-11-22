package com.pilot.server.coordination;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.pilot.core.coordination.CoordinationClaim;
import com.pilot.core.coordination.CoordinationClaimKind;
import com.pilot.core.coordination.CoordinationHandoff;
import com.pilot.core.coordination.CoordinationRefusedException;
import com.pilot.core.coordination.CoordinationRequest;
import com.pilot.core.coordination.CoordinationSnapshot;
import com.pilot.core.coordination.SessionCoordinationService;
import com.pilot.core.session.Session;
import com.pilot.core.session.SessionRepository;
import com.pilot.server.multi.WorkspaceOverviewService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 会话间显式协调的 HTTP 入口：会话标识放在路径里，所有动作都以它作为发起方。
 *
 * <p>接口只做参数与错误码转换，协议规则都在 {@link SessionCoordinationService} 里：
 * 越权代操作由服务拒绝（403），占用冲突返回占用方（409），未知对象返回 404。
 */
@Slf4j
@RestController
@RequestMapping("/api/sessions/{sessionId}/coordination")
@RequiredArgsConstructor
public class SessionCoordinationController {

    private final SessionCoordinationService coordination;
    private final SessionRepository sessions;
    private final WorkspaceOverviewService overview;
    private final SessionPresenceRegistry presence;

    /** 看板一次性拉齐：协调快照 + 现有工作区概览（沙箱、仲裁、占用者）。 */
    public record CoordinationBoard(CoordinationSnapshot snapshot, WorkspaceOverviewService.Overview overview) {
    }

    public record RequestCommand(String toSessionId, String reason, String taskId) {
    }

    public record ClaimCommand(String path, String kind, Long ttlSeconds) {
    }

    public record HandoffCommand(String toSessionId, String taskId, String summary) {
    }

    public record PathCheck(String path) {
    }

    /** 拒绝时给界面的结构化原因：码 + 占用方 + 位置，便于直接提示用户。 */
    public record Refusal(String code, String ownerSessionId, String path, String message) {
        static Refusal of(CoordinationRefusedException refused) {
            return new Refusal(refused.code(), refused.ownerSessionId(), refused.resource(), refused.getMessage());
        }
    }

    @GetMapping
    public CoordinationBoard board(@PathVariable String sessionId) {
        CoordinationSnapshot snapshot = coordination.snapshot(sessionId);
        return new CoordinationBoard(snapshot, overviewOf(sessionId));
    }

    /** 任何协调动作都说明这个会话还醒着；登记在进入处理器之前统一做。 */
    @ModelAttribute
    public void touch(@PathVariable String sessionId) {
        presence.markOnline(sessionId);
    }

    @PostMapping("/requests")
    public CoordinationRequest request(@PathVariable String sessionId, @RequestBody RequestCommand body) {
        return coordination.request(sessionId, body.toSessionId(), body.reason(), body.taskId());
    }

    /** 协作请求的收尾动作：接受、拒绝只给目标会话，撤销只给发起会话，具体权限在服务里判。 */
    @PostMapping("/requests/{requestId}/{action}")
    public CoordinationRequest resolveRequest(@PathVariable String sessionId, @PathVariable String requestId,
        @PathVariable String action) {
        return switch (action) {
            case "accept" -> coordination.acceptRequest(sessionId, requestId);
            case "decline" -> coordination.declineRequest(sessionId, requestId);
            case "withdraw" -> coordination.withdrawRequest(sessionId, requestId);
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知的请求动作: " + action);
        };
    }

    @PostMapping("/claims")
    public CoordinationClaim claim(@PathVariable String sessionId, @RequestBody ClaimCommand body) {
        return coordination.claim(sessionId, body.path(), kind(body.kind()), ttl(body.ttlSeconds()));
    }

    @DeleteMapping("/claims/{claimId}")
    public CoordinationClaim release(@PathVariable String sessionId, @PathVariable String claimId) {
        return coordination.release(sessionId, claimId);
    }

    @PostMapping("/unclaimed-check")
    public Map<String, Object> requireUnclaimed(@PathVariable String sessionId, @RequestBody PathCheck body) {
        coordination.requireUnclaimed(sessionId, body.path());
        return Map.of("path", body.path(), "claimed", false);
    }

    @PostMapping("/handoffs")
    public CoordinationHandoff handoff(@PathVariable String sessionId, @RequestBody HandoffCommand body) {
        return coordination.handoff(sessionId, body.toSessionId(), body.taskId(), body.summary());
    }

    @PostMapping("/handoffs/{handoffId}/{action}")
    public CoordinationHandoff resolveHandoff(@PathVariable String sessionId, @PathVariable String handoffId,
        @PathVariable String action) {
        return switch (action) {
            case "accept" -> coordination.acceptHandoff(sessionId, handoffId);
            case "decline" -> coordination.declineHandoff(sessionId, handoffId);
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知的交接动作: " + action);
        };
    }

    /** 接入沙箱接受/放弃前的守卫：别人的沙箱只能请求，不能代为接受或放弃。 */
    @PostMapping("/sandbox-owner-check")
    public Map<String, Object> requireSandboxOwner(@PathVariable String sessionId,
        @RequestBody Map<String, String> body) {
        coordination.requireSandboxOwner(sessionId, body.get("ownerSessionId"));
        return Map.of("ownerSessionId", body.get("ownerSessionId"), "allowed", true);
    }

    @ExceptionHandler(CoordinationRefusedException.class)
    public ResponseEntity<Refusal> refused(CoordinationRefusedException refused) {
        return ResponseEntity.status(statusOf(refused)).body(Refusal.of(refused));
    }

    private static HttpStatus statusOf(CoordinationRefusedException refused) {
        return switch (refused.code()) {
            case CoordinationRefusedException.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CoordinationRefusedException.CLAIMED, CoordinationRefusedException.CLOSED -> HttpStatus.CONFLICT;
            default -> HttpStatus.FORBIDDEN;
        };
    }

    private WorkspaceOverviewService.Overview overviewOf(String sessionId) {
        Session session = sessions.find(sessionId).orElse(null);
        if (session == null || session.workspace() == null || session.workspace().isBlank()) {
            return null;
        }
        try {
            return overview.overview(session.workspace());
        } catch (RuntimeException unavailable) {
            // 主目录被删除或不可读时，看板仍要能显示协调状态，只是没有沙箱概览。
            log.debug("会话 {} 的工作区概览不可用：{}", sessionId, unavailable.getMessage());
            return null;
        }
    }

    private static CoordinationClaimKind kind(String kind) {
        if (kind == null || kind.isBlank()) {
            return CoordinationClaimKind.FILE;
        }
        try {
            return CoordinationClaimKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的占用类型: " + kind, unknown);
        }
    }

    /** 界面传秒数；缺省或非正数时交给服务用默认租约。 */
    private static Duration ttl(Long seconds) {
        return seconds == null || seconds <= 0 ? null : Duration.ofSeconds(seconds);
    }

    /** 看板用的在线指标，便于确认在线判定是否依赖 TTL。 */
    @GetMapping("/presence")
    public Map<String, Object> presenceMetrics() {
        return Map.of("metrics", presence.metrics(), "timestamp", Instant.now().toString());
    }
}
