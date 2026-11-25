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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.pilot.core.browser.BrowserEvidenceRecord;
import com.pilot.core.browser.BrowserHandoffBroker;
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

    @Test
    void handoffAutoPausesClearsOverlayAndEvidenceEmitsToSubscribers(@TempDir Path temp) throws IOException {
        BrowserHandoffBroker broker = new BrowserHandoffBroker();
        BrowserEvidenceService evidence = new BrowserEvidenceService(temp.resolve("ev"), MAPPER);
        service.setHandoffs(broker);
        service.setEvidence(evidence);

        SseEmitter emitter = mock(SseEmitter.class);
        service.subscribe("s1", emitter);
        clearInvocations(emitter);

        when(runtime.parentSessionOf("s1::browser::b1")).thenReturn("s1");
        var future = broker.request(new BrowserHandoffBroker.PendingHandoff("h1", "s1", "s1::browser::b1", "b1", "auth",
            "请登录", "https://accounts.example/login", Instant.now(), null), Duration.ofSeconds(5));

        verify(runtime).setInteractionsPaused("s1::browser::b1", true);
        verify(runtime, timeout(2000)).clearOverlay("s1::browser::b1");
        verify(emitter, timeout(2000).atLeast(2)).send(any(SseEmitter.SseEventBuilder.class));

        Map<String, Object> resumed = service.resumeHandoff("s1", "s1::browser::b1", "已登录");
        Assertions.assertEquals(true, resumed.get("ok"));
        Assertions.assertEquals(true, future.join().completed());
        verify(runtime).setInteractionsPaused("s1::browser::b1", false);

        evidence.record(new BrowserEvidenceRecord("ev_1", "s1", "s1", "r1", "", Instant.now(), "navigate", "ok",
            "http://127.0.0.1:5173/", "Demo", "已打开", "", "", "", "", 0L, List.of(), 0, 0, ""));
        verify(emitter, timeout(2000).atLeast(3)).send(any(SseEmitter.SseEventBuilder.class));

        Assertions.assertEquals(true, service.selectLane("s1", "s1").get("ok"));
        Assertions.assertEquals(false, service.selectLane("s1", "other-session").get("ok"));
    }

    @Test
    void controlEventsAndStopControlSyncStateAndResume() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        service.subscribe("s1", emitter);
        clearInvocations(emitter);

        service.onEvent("s1", "control-state",
            MAPPER.readTree("{\"event\":\"control-state\",\"agentControlled\":true,\"controlStatus\":\"填写 e3\"}"));
        verify(runtime).recordControlState("s1", true, "填写 e3");
        verify(emitter, timeout(2000).atLeast(2)).send(any(SseEmitter.SseEventBuilder.class));

        when(runtime.agentControlled("s1")).thenReturn(true);
        when(runtime.agentControlStatus("s1")).thenReturn("填写 e3");
        var status = service.status("s1");
        Assertions.assertEquals(true, status.get("agentControlled"));
        Assertions.assertEquals("填写 e3", status.get("controlStatus"));

        var stopped = service.stopControl("s1", "s1");
        Assertions.assertEquals(true, stopped.get("ok"));
        Assertions.assertEquals(true, stopped.get("paused"));
        Assertions.assertEquals(false, stopped.get("agentControlled"));
        verify(runtime).stopControl("s1");

        clearInvocations(emitter);
        service.onEvent("s1", "control-stopped",
            MAPPER.readTree("{\"event\":\"control-stopped\",\"agentControlled\":false,\"source\":\"page\"}"));
        verify(runtime).recordControlState("s1", false, "");
        verify(runtime).setInteractionsPaused("s1", true);
        verify(emitter, timeout(2000).atLeast(2)).send(any(SseEmitter.SseEventBuilder.class));

        service.setPaused("s1", false);
        verify(runtime).setInteractionsPaused("s1", false);
        verify(runtime, timeout(2000)).setAgentControl("s1", false, "", true);
    }

    @Test
    void resolvesEvidenceRecordingUnderManagedRootAndSummarizesCount(@TempDir Path temp) throws IOException {
        Path evRoot = temp.resolve("ev");
        BrowserEvidenceService evidence = new BrowserEvidenceService(evRoot, MAPPER);
        service.setEvidence(evidence);

        Path recDir = Files.createDirectories(evRoot.resolve("recordings"));
        Path recFile =
            Files.writeString(recDir.resolve("run-1.replay.json"), "{\"version\":1,\"frames\":[],\"actions\":[]}");
        Path outsideFile = Files.writeString(temp.resolve("escape.replay.json"), "{}");

        evidence.record(new BrowserEvidenceRecord("ev_rec", "s1", "s1", "r1", "ba_1", Instant.now(), "record", "ok",
            "http://127.0.0.1:5173/", "Demo", "已录制", "", "", "", "", 0L, List.of(), 0, 0, "", recFile.toString()));
        evidence.record(new BrowserEvidenceRecord("ev_escape", "s1", "s1", "r1", "ba_1", Instant.now(), "record", "ok",
            "http://127.0.0.1:5173/", "Demo", "越界路径", "", "", "", "", 0L, List.of(), 0, 0, "", outsideFile.toString()));

        Assertions.assertEquals(recFile.toAbsolutePath().normalize(),
            service.resolveEvidenceRecording("s1", "ev_rec").orElseThrow());
        Assertions.assertTrue(service.resolveEvidenceRecording("s1", "ev_escape").isEmpty(), "受控根目录之外的录像路径必须拒绝读取");
        Map<String, Object> resp = service.evidence("s1", "", 20);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>)resp.get("summary");
        Assertions.assertEquals(2, summary.get("recordings"));
    }
}
