package com.pilot.server.work;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.pilot.core.session.Session;
import com.pilot.core.session.SessionService;
import com.pilot.core.util.Sha256;
import com.pilot.core.util.WireEnum;
import com.pilot.server.skill.SkillService;
import com.pilot.server.store.AutomationStore;
import com.pilot.server.tool.WorkspaceToolFactory;

import tools.jackson.databind.ObjectMapper;

/** 模型只提出草稿。人工接受/撤回先记意图再写文件，重启后根据内容指纹收敛状态。 */
@Service
public class SkillDraftService {
    private static final String KIND = "skill-draft";
    private static final String ID_PREFIX = "skill_";
    private static final int MAX_SKILL_BYTES = 128 * 1024;
    private static final int MAX_DESCRIPTION_CHARS = 500;
    private static final int MAX_BODY_CHARS = 32_000;
    private static final int MAX_EVIDENCE_CHARS = 2_000;

    /** 人工对草稿的操作；wire 是接口里 action 的取值。 */
    private enum Action {
        APPROVE("approve"), REVERT("revert"), REJECT("reject");

        private final String wire;

        Action(String wire) {
            this.wire = wire;
        }

        static Action parse(String value) {
            for (Action action : values()) {
                if (action.wire.equals(value)) {
                    return action;
                }
            }
            throw new IllegalArgumentException("不支持的草稿操作");
        }
    }

    public record Draft(String id, String sessionId, String runId, String workspace, String name, String description,
        String body, String evidence, String previousContent, String baseHash, String installedHash, String status,
        long version, Instant createdAt, Instant updatedAt) {
        Draft transition(SkillDraftStatus next) {
            return new Draft(id, sessionId, runId, workspace, name, description, body, evidence, previousContent,
                baseHash, installedHash, next.name(), version + 1, createdAt, Instant.now());
        }
    }

    private final AutomationStore store;
    private final ObjectMapper mapper;
    private final SessionService sessions;
    private final WorkspaceToolFactory workspaces;
    private final SkillService skills;

    public SkillDraftService(AutomationStore store, ObjectMapper mapper, SessionService sessions,
        WorkspaceToolFactory workspaces, SkillService skills) {
        this.store = store;
        this.mapper = mapper;
        this.sessions = sessions;
        this.workspaces = workspaces;
        this.skills = skills;
    }

    public synchronized Draft propose(String sessionId, String runId, String name, String description, String body,
        String evidence) {
        requireEnabled();
        Path workspace = workspace(sessionId);
        if (name == null || !name.matches("[a-z][a-z0-9-]{0,63}")
            || name.matches("con|prn|aux|nul|com[1-9]|lpt[1-9]")) {
            throw new IllegalArgumentException("技能名称须为 1–64 位小写字母、数字或连字符，并以字母开头");
        }
        String detail = WorkProductService.required(description, "适用场景", MAX_DESCRIPTION_CHARS);
        if (detail.contains("\n") || detail.contains("\r")) {
            throw new IllegalArgumentException("适用场景必须写在一行中");
        }
        String steps = WorkProductService.required(body, "技能步骤", MAX_BODY_CHARS);
        String proof = WorkProductService.required(evidence, "经验依据", MAX_EVIDENCE_CHARS);
        String content = content(name, detail, steps);
        Path target = target(workspace, name);
        String previous = read(target);
        String baseHash = hash(previous);
        String installedHash = hash(content);
        String identity = mapper
            .writeValueAsString(List.of(sessionId, Objects.toString(runId, ""), name, baseHash, installedHash, proof));
        String id = ID_PREFIX + Sha256.hex(identity).substring(0, 32);
        if (store.get(KIND, id).isPresent()) {
            return get(sessionId, id);
        }
        Instant now = Instant.now();
        Draft draft = new Draft(id, sessionId, Objects.toString(runId, ""), workspace.toString(), name, detail, steps,
            proof, previous, baseHash, installedHash, SkillDraftStatus.PENDING.name(), 1, now, now);
        if (!store.put(KIND, id, 0, mapper.writeValueAsString(draft))) {
            return get(sessionId, id);
        }
        return draft;
    }

    public synchronized List<Draft> list(String sessionId) {
        requireSession(sessionId);
        return store.list(KIND).stream().map(row -> mapper.readValue(row.payload(), Draft.class))
            .filter(draft -> draft.sessionId().equals(sessionId)).map(this::recover)
            .sorted(Comparator.comparing(Draft::createdAt).reversed()).toList();
    }

    public synchronized Draft get(String sessionId, String id) {
        requireSession(sessionId);
        Draft draft = store.get(KIND, id).map(row -> mapper.readValue(row.payload(), Draft.class))
            .orElseThrow(() -> new NoSuchElementException("技能草稿不存在"));
        if (!draft.sessionId().equals(sessionId)) {
            throw new NoSuchElementException("技能草稿不属于当前对话");
        }
        return recover(draft);
    }

    /** 删除对话记录不会卸载已接受的项目技能。 */
    public synchronized void deleteBySession(String sessionId) {
        for (var row : store.list(KIND)) {
            Draft draft = mapper.readValue(row.payload(), Draft.class);
            if (draft.sessionId().equals(sessionId)) {
                store.delete(KIND, row.id(), row.version());
            }
        }
    }

    public synchronized Draft decide(String sessionId, String id, long version, String action) {
        requireEnabled();
        Draft draft = get(sessionId, id);
        if (draft.version() != version) {
            throw new IllegalStateException("草稿状态已变化，请刷新后核对");
        }
        Action decision = Action.parse(action);
        if (decision == Action.REJECT) {
            if (!WireEnum.in(draft.status(), SkillDraftStatus.PENDING, SkillDraftStatus.CONFLICT)) {
                throw new IllegalStateException("当前草稿不能拒绝");
            }
            return save(draft, SkillDraftStatus.REJECTED);
        }
        boolean approve = decision == Action.APPROVE;
        if (!(approve ? SkillDraftStatus.PENDING : SkillDraftStatus.APPROVED).is(draft.status())) {
            throw new IllegalStateException(approve ? "只能接受待审核草稿" : "只能撤回已接受的版本");
        }
        Path target = checkedTarget(draft);
        String expected = approve ? draft.baseHash() : draft.installedHash();
        if (!hash(read(target)).equals(expected)) {
            throw new IllegalStateException("技能文件已被其他操作修改，未覆盖；请重新生成草稿或核对当前文件");
        }
        Draft intent = save(draft, approve ? SkillDraftStatus.APPROVING : SkillDraftStatus.REVERTING);
        replace(Path.of(draft.workspace()), target,
            approve ? content(draft.name(), draft.description(), draft.body()) : draft.previousContent(), expected);
        return save(intent, approve ? SkillDraftStatus.APPROVED : SkillDraftStatus.REVERTED);
    }

    private Draft recover(Draft draft) {
        if (!WireEnum.in(draft.status(), SkillDraftStatus.APPROVING, SkillDraftStatus.REVERTING)) {
            return draft;
        }
        String current;
        try {
            current = hash(read(checkedTarget(draft)));
        } catch (IllegalStateException | UncheckedIOException e) {
            return save(draft, SkillDraftStatus.CONFLICT);
        }
        boolean approving = SkillDraftStatus.APPROVING.is(draft.status());
        if (current.equals(approving ? draft.installedHash() : draft.baseHash())) {
            return save(draft, approving ? SkillDraftStatus.APPROVED : SkillDraftStatus.REVERTED);
        }
        if (current.equals(approving ? draft.baseHash() : draft.installedHash())) {
            // 文件操作尚未发生，回到可由用户重试的状态；不会静默重放写操作。
            return save(draft, approving ? SkillDraftStatus.PENDING : SkillDraftStatus.APPROVED);
        }
        return save(draft, SkillDraftStatus.CONFLICT);
    }

    private Draft save(Draft draft, SkillDraftStatus status) {
        Draft next = draft.transition(status);
        if (!store.put(KIND, draft.id(), draft.version(), mapper.writeValueAsString(next))) {
            throw new IllegalStateException("草稿状态已变化，请刷新");
        }
        return next;
    }

    private Path checkedTarget(Draft draft) {
        Path current = workspace(draft.sessionId());
        if (!current.toString().equals(draft.workspace())) {
            throw new IllegalStateException("对话工作区已变化，不能把旧草稿应用到其他目录");
        }
        return target(current, draft.name());
    }

    private Path target(Path workspace, String name) {
        Path cursor = workspace;
        for (String part : List.of(".pilot", "skills", name, "SKILL.md")) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalStateException("技能目录或文件是符号链接，请先核对真实位置");
            }
        }
        return cursor;
    }

    private String read(Path file) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_SKILL_BYTES) {
                throw new IllegalStateException("现有技能不是普通文件或超过 128 KiB，不能覆盖");
            }
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAX_SKILL_BYTES + 1);
                if (bytes.length > MAX_SKILL_BYTES) {
                    throw new IllegalStateException("技能文件过大，不能覆盖");
                }
                return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取技能文件", e);
        }
    }

    private void replace(Path workspace, Path file, String content, String expected) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            // 目录创建后再检查一次，避免普通并发编辑被当作当前批准的版本。
            target(workspace, file.getParent().getFileName().toString());
            if (!hash(read(file)).equals(expected)) {
                throw new IllegalStateException("技能文件已变化，操作未执行");
            }
            if (content == null) {
                Files.deleteIfExists(file);
                try {
                    Files.delete(file.getParent());
                } catch (DirectoryNotEmptyException ignored) {
                    // 只清理空目录，保留用户另外加入的资源。
                }
                return;
            }
            temporary = Files.createTempFile(file.getParent(), ".skill-", ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("技能写入未完成，刷新后可核对恢复状态", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 临时文件不是 SKILL.md，目录扫描不会把它当作已安装技能。
                }
            }
        }
    }

    private Path workspace(String sessionId) {
        var session = requireSession(sessionId);
        try {
            return workspaces.resolve(session.workspace()).toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("工作区不可用", e);
        }
    }

    private Session requireSession(String sessionId) {
        return sessions.find(sessionId).orElseThrow(() -> new NoSuchElementException("对话不存在"));
    }

    private void requireEnabled() {
        if (!skills.enabled()) {
            throw new IllegalStateException("技能功能已关闭");
        }
    }

    private static String content(String name, String description, String body) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body + "\n";
    }

    private static String hash(String content) {
        return content == null ? "absent" : Sha256.hex(content);
    }
}
