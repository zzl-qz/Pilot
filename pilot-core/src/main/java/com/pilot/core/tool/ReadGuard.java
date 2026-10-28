package com.pilot.core.tool;

import java.nio.file.Path;

/**
 * 「先读后改」的运行级记录：read_file 成功后登记路径，edit_file 执行前校验——
 * 本运行没读过的文件直接拒绝并返回补救指引。此前这条规则只写在 workflow.md 的提示词里，
 * 模型可以绕过；机制化之后不再依赖模型自觉。
 *
 * <p>豁免规则与 dsh 的 fs-observation-policy 一致：本运行刚创建或刚编辑过的文件视为已读
 * （create_file / edit_file 成功后同样登记），连续编辑不会被误伤。
 *
 * <p>生命周期为一次 Agent 运行（而非整个会话）：续跑与新消息的运行各自归零，
 * 模型会先被拒一次再补读——一次额外 read_file 换取确定性，可接受。
 */
public interface ReadGuard {

    /** 记录一个已被本运行观察过（读取 / 创建 / 编辑）的文件。 */
    void observed(Path file);

    /** 该文件是否已在本运行观察过。 */
    boolean isObserved(Path file);

    /** 全放行：测试与未装配场景的默认值。 */
    static ReadGuard allowAll() {
        return new ReadGuard() {
            @Override
            public void observed(Path file) {
            }

            @Override
            public boolean isObserved(Path file) {
                return true;
            }
        };
    }
}
