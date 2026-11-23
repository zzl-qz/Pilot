package com.pilot.server.coordination;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.pilot.core.coordination.CoordinationClaim;
import com.pilot.core.coordination.CoordinationClaimKind;
import com.pilot.core.coordination.CoordinationHandoff;
import com.pilot.core.coordination.CoordinationHandoffStatus;
import com.pilot.core.coordination.CoordinationRequest;
import com.pilot.core.coordination.CoordinationRequestStatus;
import com.pilot.core.coordination.CoordinationSnapshot;
import com.pilot.core.coordination.CoordinationSnapshotStore;
import com.pilot.server.store.PilotDataDir;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 协调状态的落盘实现：整份快照写入 {@code coordination/state.json}，先写临时文件再原子替换。
 *
 * <p>落盘格式只含协作请求、占用与交接；在线状态是运行时数据，故意不写进来，
 * 重启后不会出现「已经离线的会话仍被当成在线」。
 */
@Component
public class CoordinationStateStore implements CoordinationSnapshotStore {

    private final ObjectMapper mapper;
    private final PilotDataDir dataDir;

    public CoordinationStateStore(ObjectMapper mapper, PilotDataDir dataDir) {
        this.mapper = mapper;
        this.dataDir = dataDir;
    }

    @Override
    public CoordinationSnapshot load() {
        Path file = stateFile();
        if (!Files.isRegularFile(file)) {
            return CoordinationSnapshot.empty();
        }
        try {
            StatePayload payload = mapper.readValue(Files.readString(file), new TypeReference<StatePayload>() {});
            return payload == null ? CoordinationSnapshot.empty() : payload.toSnapshot();
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("协调状态损坏，拒绝继续操作: " + file.getFileName(), failure);
        }
    }

    @Override
    public void save(CoordinationSnapshot snapshot) {
        Path file = stateFile();
        try {
            Files.createDirectories(file.getParent());
            writeAtomically(file, mapper.writeValueAsString(StatePayload.of(snapshot)));
        } catch (IOException failure) {
            throw new UncheckedIOException("协调状态持久化失败", failure);
        }
    }

    private Path stateFile() {
        return dataDir.file("coordination", "state.json");
    }

    private static void writeAtomically(Path file, String body) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), "coordination", ".tmp");
        Files.writeString(temp, body, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 落盘格式：时间统一按 ISO-8601 字符串保存，读回时严格解析，坏数据直接报错。 */
    private record ClaimPayload(String id, String projectId, String sessionId, String path, String kind,
        String claimedAt, String expiresAt) {
        static ClaimPayload of(CoordinationClaim claim) {
            return new ClaimPayload(claim.id(), claim.projectId(), claim.sessionId(), claim.path(), claim.kind().name(),
                claim.claimedAt().toString(), claim.expiresAt().toString());
        }

        CoordinationClaim toClaim() {
            return new CoordinationClaim(id, projectId, sessionId, path, CoordinationClaimKind.valueOf(kind),
                Instant.parse(claimedAt), Instant.parse(expiresAt));
        }
    }

    private record RequestPayload(String id, String projectId, String fromSessionId, String toSessionId, String reason,
        String taskId, String status, String createdAt, String updatedAt, String expiresAt) {
        static RequestPayload of(CoordinationRequest request) {
            return new RequestPayload(request.id(), request.projectId(), request.fromSessionId(), request.toSessionId(),
                request.reason(), request.taskId(), request.status().name(), request.createdAt().toString(),
                request.updatedAt().toString(), request.expiresAt().toString());
        }

        CoordinationRequest toRequest() {
            return new CoordinationRequest(id, projectId, fromSessionId, toSessionId, reason, taskId,
                CoordinationRequestStatus.valueOf(status), Instant.parse(createdAt), Instant.parse(updatedAt),
                expiresAt == null || expiresAt.isBlank() ? null : Instant.parse(expiresAt));
        }
    }

    private record HandoffPayload(String id, String projectId, String fromSessionId, String toSessionId, String taskId,
        String summary, String status, String createdAt, String updatedAt, String expiresAt) {
        static HandoffPayload of(CoordinationHandoff handoff) {
            return new HandoffPayload(handoff.id(), handoff.projectId(), handoff.fromSessionId(), handoff.toSessionId(),
                handoff.taskId(), handoff.summary(), handoff.status().name(), handoff.createdAt().toString(),
                handoff.updatedAt().toString(), handoff.expiresAt().toString());
        }

        CoordinationHandoff toHandoff() {
            return new CoordinationHandoff(id, projectId, fromSessionId, toSessionId, taskId, summary,
                CoordinationHandoffStatus.valueOf(status), Instant.parse(createdAt), Instant.parse(updatedAt),
                Instant.parse(expiresAt));
        }
    }

    private record StatePayload(List<ClaimPayload> claims, List<RequestPayload> requests,
        List<HandoffPayload> handoffs) {
        static StatePayload of(CoordinationSnapshot snapshot) {
            return new StatePayload(snapshot.claims().stream().map(ClaimPayload::of).toList(),
                snapshot.requests().stream().map(RequestPayload::of).toList(),
                snapshot.handoffs().stream().map(HandoffPayload::of).toList());
        }

        CoordinationSnapshot toSnapshot() {
            return new CoordinationSnapshot(null, List.of(),
                claims == null ? List.of() : claims.stream().map(ClaimPayload::toClaim).toList(),
                requests == null ? List.of() : requests.stream().map(RequestPayload::toRequest).toList(),
                handoffs == null ? List.of() : handoffs.stream().map(HandoffPayload::toHandoff).toList());
        }
    }
}
