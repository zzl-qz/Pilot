package com.pilot.core.tool;

import java.util.Map;

/**
 * 标记「只作用于隔离的本机沙箱、本身不写工作区文件」的操作，例如在隔离浏览器里点击本机页面。
 *
 * <p>READ_ONLY 仍然拒绝它们（页面里的「删除」按钮同样会改变应用状态），ASK 逐次审批；
 * AUTO_EDIT 直接放行——否则一次页面验证要点几十次审批，等于不能用。
 */
public interface LocalInteraction {

    /** 本次调用是否属于这类操作；其余调用（例如执行任意脚本）仍走常规审批。 */
    boolean localInteraction(Map<String, Object> args);
}
