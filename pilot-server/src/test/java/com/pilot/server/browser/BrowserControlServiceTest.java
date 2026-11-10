package com.pilot.server.browser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.pilot.core.tool.Availability;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 镜像分发的瞬态行为：latest-wins、订阅联动（首连开帧/断连停帧）、异常订阅者被静默摘除。
 *
 * <p>BrowserRuntime 用 Mockito 假实现（真实的要拉起 Node sidecar）；
 * SseEmitter 同样 mock，只验证「向谁推了几次什么」，不关心 SSE 序列化细节。
 */
class BrowserControlServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BrowserRuntime runtime;
    private BrowserControlService service;

    @BeforeEach
    void setUp() {
        runtime = mock(BrowserRuntime.class);
        when(runtime.availability()).thenReturn(Availability.ready());
        when(runtime.interactionsPaused(any())).thenReturn(false);
        service = new BrowserControlService(runtime, MAPPER, true, 960, 55);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private static JsonNode event(String kind) {
        return MAPPER.readTree("{\"event\":\"" + kind + "\",\"session\":\"s1\",\"frame\":\"<jpeg>\"}");
    }

    @Test
    void firstSubscriberTurnsMirrorOnAndGetsStateAndLatestFrame() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        service.subscribe("s1", emitter);

        // 首个订阅者出现 → 异步开启该会话的 screencast
        verify(runtime, timeout(2000)).mirrorControl("s1", true, 960, 55);
        // 连接即补发 state（帧尚未到达时不强推）
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(runtime, never()).mirrorControl(any(), eq(false), anyInt(), anyInt());
    }

    @Test
    void stateSubscribersNeverEnableOrReceiveScreenshotsAndReplayNavigation() throws IOException {
        SseEmitter stateOnly = mock(SseEmitter.class);
        service.subscribeState("s1", stateOnly);
        verify(runtime, timeout(2000)).mirrorCapture("s1", false, 960, 55);
        verify(runtime, never()).mirrorControl("s1", true, 960, 55);
        service.onEvent("s1", "mirror-frame", event("mirror-frame"));
        verify(stateOnly).send(any(SseEmitter.SseEventBuilder.class));

        service.onEvent("s1", "nav", MAPPER.readTree("{\"url\":\"http://localhost:1234/\"}"));
        SseEmitter late = mock(SseEmitter.class);
        service.subscribeState("s1", late);
        verify(late, timeout(2000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
        service.setCapture("s1", true);
        verify(runtime, never()).mirrorControl("s1", true, 960, 55);
    }

    @Test
    void mixedSubscribersOnlyCaptureWhileAFrameConsumerRemains() throws IOException {
        SseEmitter status = mock(SseEmitter.class);
        SseEmitter frames = mock(SseEmitter.class);
        service.subscribeState("s1", status);
        verify(runtime, timeout(2000)).mirrorCapture("s1", false, 960, 55);
        service.subscribe("s1", frames);
        verify(runtime, timeout(2000)).mirrorControl("s1", true, 960, 55);
        clearInvocations(status, frames);
        service.onEvent("s1", "mirror-frame", event("mirror-frame"));
        verify(frames).send(any(SseEmitter.SseEventBuilder.class));
        verify(status, never()).send(any(SseEmitter.SseEventBuilder.class));

        service.unsubscribe("s1", frames);
        verify(runtime, timeout(2000).times(2)).mirrorCapture("s1", false, 960, 55);
        service.onEvent("s1", "mirror-frame", event("mirror-frame"));
        verify(status, never()).send(any(SseEmitter.SseEventBuilder.class));
        service.onEvent("s1", "nav", event("nav"));
        verify(status).send(any(SseEmitter.SseEventBuilder.class));
        service.unsubscribe("s1", status);
        verify(runtime, timeout(2000)).mirrorControl("s1", false, 960, 55);
    }

    @Test
    void brokenFrameConsumerStopsCaptureWhileStatusConnectionStaysAlive() throws IOException {
        SseEmitter status = mock(SseEmitter.class);
        service.subscribeState("s1", status);
        SseEmitter broken = mock(SseEmitter.class);
        doThrow(new IOException("连接断开")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        service.subscribe("s1", broken);
        verify(runtime, timeout(2000).times(2)).mirrorCapture("s1", false, 960, 55);
        verify(runtime, never()).mirrorControl("s1", false, 960, 55);
        service.onEvent("s1", "nav", event("nav"));
        verify(status, timeout(2000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void framesAreLatestWinsAndSlowSubscribersDoNotQueue() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        service.subscribe("s1", emitter);

        service.onEvent("s1", "mirror-frame", event("mirror-frame"));
        service.onEvent("s1", "mirror-frame", event("mirror-frame"));

        // 两帧都即时投递（不排队，慢消费者由 SSE 写失败自然丢弃）
        verify(emitter, timeout(2000).times(3)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void secondSubscriberReplaysCachedFrameAndKeepsMirrorOn() throws IOException {
        service.subscribe("s1", mock(SseEmitter.class));
        service.onEvent("s1", "mirror-frame", event("mirror-frame"));
        SseEmitter late = mock(SseEmitter.class);

        service.subscribe("s1", late);

        // 断线重连/多端订阅：补发缓存的最新帧；不再重复开 screencast
        verify(late, timeout(2000).times(2)).send(any(SseEmitter.SseEventBuilder.class));
        // 帧补发是同步的，控制命令是异步的；收到帧不代表控制线程已经执行。
        verify(runtime, timeout(2000)).mirrorControl("s1", true, 960, 55);
    }

    @Test
    void brokenSubscriberIsDroppedAndMirrorStopsWhenNobodyWatches() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("对端断开")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        service.subscribe("s1", emitter);

        // 补发 state 就会失败 → 订阅被摘，最后一个断开联动停帧
        verify(runtime, timeout(2000)).mirrorControl("s1", false, 960, 55);
    }

    @Test
    void unsubscribeIsIdempotentAndLastLeaveStopsMirror() {
        SseEmitter emitter = mock(SseEmitter.class);
        service.subscribe("s1", emitter);

        service.unsubscribe("s1", emitter);
        service.unsubscribe("s1", emitter); // 重复断开（completion + error 双回调）不二次停帧

        verify(runtime, timeout(2000)).mirrorControl("s1", false, 960, 55);
    }

    @Test
    void browserClosedEventClearsFramesAndNotifiesEverySession() throws IOException {
        SseEmitter one = mock(SseEmitter.class);
        SseEmitter two = mock(SseEmitter.class);
        service.subscribe("s1", one);
        service.subscribe("s2", two);
        service.onEvent("s1", "mirror-frame", event("mirror-frame"));

        // 进程级事件：浏览器断开，两个会话都收到 closed
        service.onEvent("", "browser-closed", event("browser-closed"));

        verify(one, timeout(2000).atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
        verify(two, timeout(2000).atLeastOnce()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void unknownEventsAreIgnoredForForwardCompatibility() {
        assertDoesNotThrow(() -> service.onEvent("s1", "whatever-new-event", event("whatever-new-event")));
        // 未知事件不触发任何镜像联动（构造时的 setEventListener 除外）
        verify(runtime, never()).mirrorControl(any(), anyBoolean(), anyInt(), anyInt());
        verify(runtime, never()).focusPage(any());
    }

    @Test
    void statusReflectsCapabilityAndPauseState() {
        when(runtime.mirrorCapable()).thenReturn(true);
        when(runtime.interactionsPaused("s1")).thenReturn(true);
        when(runtime.headless()).thenReturn(true);

        var status = service.status("s1");

        Assertions.assertEquals(true, status.get("enabled"));
        Assertions.assertEquals(true, status.get("capable"));
        Assertions.assertEquals(true, status.get("available"));
        Assertions.assertEquals(true, status.get("paused"));
        // 后台模式标记：前端据此隐藏「置前窗口」（没有窗口可置前）
        Assertions.assertEquals(true, status.get("headless"));
    }

    @Test
    void pauseFlowsToRuntimeAndStateEventGoesBackToSubscribers() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        service.subscribe("s1", emitter);

        service.setPaused("s1", true);

        verify(runtime).setInteractionsPaused("s1", true);
        // 状态变化回推给订阅者（前端按钮按这个翻转，双击不会不一致）
        verify(emitter, timeout(2000).atLeast(2)).send(any(SseEmitter.SseEventBuilder.class));

        service.setPaused("s1", false);
        verify(runtime).setInteractionsPaused("s1", false);
    }

    @Test
    void takeoverFlagIsResetWhenTheLastSubscriberLeaves() {
        SseEmitter emitter = mock(SseEmitter.class);
        service.subscribe("s1", emitter);
        service.setPaused("s1", true);

        service.unsubscribe("s1", emitter);

        // 没人看着页面就不算接管中：订阅清空即复位，下个订阅者拿到干净状态
        verify(runtime, timeout(2000)).setInteractionsPaused("s1", false);
    }
}
