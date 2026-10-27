package com.pilot.core.multi;

/**
 * 一条沙箱改动：完整 diff + 改动后的完整内容 + 归属。
 *
 * <p>只保存文本，不保存文件句柄；用户点「接受」时才按 {@code newContent} 落盘，
 * 因此子 Agent 再怎么折腾都碰不到真实仓库。
 *
 * @param path       工作区相对路径
 * @param diff       unified diff，用于审阅与冲突检测
 * @param newContent 改动后的完整文件内容；接受变更时直接写入
 * @param existed    改动前文件是否存在（区分新增与修改）
 * @param baseHash   改动前内容的 SHA-256，接受变更时校验文件没被别人改过
 * @param agentId    产出该改动的子 Agent
 * @param taskId     归属任务
 * @param risk       该任务的风险等级，决定是否强制人工确认
 */
public record SandboxChange(String path, String diff, String newContent, boolean existed,
                            String baseHash, String agentId, String taskId, RiskLevel risk,
                            ChangeStatus status) {
    public SandboxChange {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("改动路径不能为空");
        }
        diff = diff == null ? "" : diff;
        newContent = newContent == null ? "" : newContent;
        baseHash = baseHash == null ? "" : baseHash;
        risk = risk == null ? RiskLevel.MEDIUM : risk;
        status = status == null ? ChangeStatus.PENDING : status;
    }

    public int added() {
        return UnifiedDiff.countChanges(diff)[0];
    }

    public int removed() {
        return UnifiedDiff.countChanges(diff)[1];
    }

    public SandboxChange withStatus(ChangeStatus next) {
        return new SandboxChange(path, diff, newContent, existed, baseHash, agentId, taskId, risk, next);
    }
}
