package com.pilot.core.browser;

import com.pilot.core.tool.Availability;

import java.time.Duration;
import java.util.Map;

/**
 * 浏览器运行时的抽象：{@code browser} 工具只依赖它，真实实现是 Playwright sidecar，
 * 测试里用假实现，不必真的拉起浏览器。
 *
 * <p>会话隔离由实现保证：不同 {@code sessionId} 的 cookie、storage、页面互不可见。
 */
public interface BrowserBackend {

    /**
     * 当前能否使用。{@code browser} 工具据此决定是否向模型声明——
     * 进程没起来、握手没完成、本机没有浏览器时必须返回不可用并给出原因。
     */
    Availability availability();

    /**
     * 在某个会话的浏览器里执行一条命令。
     *
     * @param command 至少含 {@code op}，其余是该操作的参数
     * @throws BrowserException 操作失败；消息面向模型，说明原因与下一步
     */
    Reply execute(String sessionId, Map<String, Object> command, Duration timeout);

    /** 关掉某个会话的浏览器上下文，释放页面与存储。 */
    void closeSession(String sessionId);

    /**
     * 注册 sidecar 主动事件的监听者。运行时未就绪时先保存，backend 建立后自动转移；
     * 传 {@code null} 注销。实现未支持事件通道时静默忽略（默认无操作）。
     */
    default void setEventListener(BrowserEventListener listener) {
    }

    /**
     * 开/关某个会话的页面镜像推帧（CDP screencast）。
     * 运行时未就绪或没有活动页面时静默忽略——sidecar 会登记请求，浏览器启动后自然生效。
     *
     * @param maxWidth  帧宽上限（像素）
     * @param quality JPEG 质量（20-90）
     */
    default void mirrorControl(String sessionId, boolean start, int maxWidth, int quality) {
    }

    /** 把某个会话的浏览器窗口置前；无活动页面时静默忽略。 */
    default void focusPage(String sessionId) {
    }

    /** 智能体的交互动作是否被用户暂停（接管中）；不支持接管的实现恒为 {@code false}。 */
    default boolean interactionsPaused(String sessionId) {
        return false;
    }

    /**
     * @param text       给模型看的文字结果
     * @param screenshot 截图文件的绝对路径；没有截图时为 {@code null}
     */
    record Reply(String text, String screenshot) {
    }

    /** 浏览器操作失败。 */
    class BrowserException extends RuntimeException {
        public BrowserException(String message) {
            super(message);
        }
    }
}
