package com.pilot.core.permission;

/**
 * 权限档位。
 *
 * <ul>
 *   <li>{@link #READ_ONLY}：在 Codex 三档之外加的只读。只能看，写入和会改状态的命令直接拒绝</li>
 *   <li>{@link #ASK}：Codex Suggest。只读直接做，改文件和每条命令都要批准</li>
 *   <li>{@link #AUTO_EDIT}：Codex Auto Edit。改文件直接生效，命令仍要批准</li>
 *   <li>{@link #AUTO}：Codex Full Auto。工作区内的写入和命令直接执行，包括 git 提交和推送。
 *       发布包、全局安装，以及工具自己标成危险的动作仍要确认</li>
 * </ul>
 */
public enum PermissionMode {
    READ_ONLY, ASK, AUTO_EDIT, AUTO
}
