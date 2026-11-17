package com.pilot.core.quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pilot.core.quality.CodeAssetIndex.ConstantRef;
import com.pilot.core.quality.ConstantAffinityMatcher.MatchContext;

class ConstantAffinityMatcherTest {

    @Test
    void distinguishesSameDomainFromForeignDomainStatusCollisions() {
        List<ConstantRef> candidates =
            List.of(new ConstantRef("InvoiceStatus.PAID", "src/main/java/demo/billing/InvoiceStatus.java", "PAID"),
                new ConstantRef("OrderStatus.PAID", "src/main/java/demo/order/OrderStatus.java", "PAID"));

        // 1. 在订单模块中写 "PAID"：精准命中同领域的 OrderStatus.PAID，绝不混入 InvoiceStatus.PAID
        String orderAdvice = ConstantAffinityMatcher.advise(candidates, MatchContext.of("PAID", false,
            "src/main/java/demo/order/OrderService.java", "String state = \"PAID\";", Set.of(), Set.of()));
        assertTrue(orderAdvice.contains("OrderStatus.PAID"), orderAdvice);
        assertFalse(orderAdvice.contains("InvoiceStatus.PAID"), orderAdvice);

        // 2. 在完全无关的物流模块写 "PAID"：两个异领域枚举全部排斥，要求在物流模块独立定义
        String shippingAdvice = ConstantAffinityMatcher.advise(candidates, MatchContext.of("PAID", false,
            "src/main/java/demo/shipping/ShipmentService.java", "String state = \"PAID\";", Set.of(), Set.of()));
        assertFalse(shippingAdvice.contains("InvoiceStatus.PAID"), shippingAdvice);
        assertFalse(shippingAdvice.contains("OrderStatus.PAID"), shippingAdvice);
        assertTrue(shippingAdvice.contains("切勿跨领域混用"), shippingAdvice);
    }

    @Test
    void surfacesMultipleAmbiguousCandidatesInsteadOfPickingFirstBlindly() {
        List<ConstantRef> candidates =
            List.of(new ConstantRef("OrderState.PENDING", "src/main/java/demo/order/OrderState.java", "PENDING"),
                new ConstantRef("OrderAuditState.PENDING", "src/main/java/demo/order/OrderAuditState.java", "PENDING"));

        String advice = ConstantAffinityMatcher.advise(candidates, MatchContext.of("PENDING", false,
            "src/main/java/demo/order/OrderProcessor.java", "if (\"PENDING\".equals(current)) {", Set.of(), Set.of()));

        assertTrue(advice.contains("多个同名或同值定义"), advice);
        assertTrue(advice.contains("OrderState.PENDING"), advice);
        assertTrue(advice.contains("OrderAuditState.PENDING"), advice);
    }

    @Test
    void blocksCoincidentalNumericEqualityAndUnitClashes() {
        List<ConstantRef> pageConst =
            List.of(new ConstantRef("PageConstants.DEFAULT_PAGE_SIZE", "src/common/PageConstants.java", "50"));

        // 语义一致（分页）-> 推荐
        String pageAdvice = ConstantAffinityMatcher.advise(pageConst,
            MatchContext.of("50", true, "src/order/OrderQuery.java", "int pageSize = 50;", Set.of(), Set.of()));
        assertTrue(pageAdvice.contains("PageConstants.DEFAULT_PAGE_SIZE"), pageAdvice);

        // 巧合同值但语义不同（密码长度 / 重试次数）-> 严禁推荐分页常量
        String passwordAdvice = ConstantAffinityMatcher.advise(pageConst, MatchContext.of("50", true,
            "src/auth/AuthValidator.java", "if (password.length() > 50) {", Set.of(), Set.of()));
        assertFalse(passwordAdvice.contains("DEFAULT_PAGE_SIZE"), passwordAdvice);

        // 单位冲突：常量是 3600 秒，代码是 sleep(3600) 毫秒 -> 严禁推荐
        List<ConstantRef> secConst =
            List.of(new ConstantRef("TimeConstants.HOUR_SECONDS", "src/common/TimeConstants.java", "3600"));
        String sleepAdvice = ConstantAffinityMatcher.advise(secConst,
            MatchContext.of("3600", true, "src/worker/Poller.java", "Thread.sleep(3600);", Set.of(), Set.of()));
        assertFalse(sleepAdvice.contains("HOUR_SECONDS"), sleepAdvice);

        // 单位吻合：代码是 ttlSeconds = 3600 -> 正常推荐
        String ttlAdvice = ConstantAffinityMatcher.advise(secConst,
            MatchContext.of("3600", true, "src/worker/TokenCache.java", "int ttlSeconds = 3600;", Set.of(), Set.of()));
        assertTrue(ttlAdvice.contains("TimeConstants.HOUR_SECONDS"), ttlAdvice);
    }

    @Test
    void protectsExternalWebhookProtocolAndModuleDependencyBoundaries() {
        List<ConstantRef> internal = List.of(
            new ConstantRef("RunStatus.SUCCESS", "pilot-core/src/main/java/com/pilot/core/RunStatus.java", "SUCCESS"),
            new ConstantRef("ServerConst.PORT", "pilot-server/src/main/java/com/pilot/server/ServerConst.java", "8080"),
            new ConstantRef("WebConst.PORT", "pilot-web/frontend/src/lib/constants.ts", "8080"));

        // 外部第三方回调报文：提醒协议边界，不硬塞内部枚举
        String webhookAdvice = ConstantAffinityMatcher.advise(internal,
            MatchContext.of("SUCCESS", false, "pilot-server/src/main/java/com/pilot/server/WechatController.java",
                "if (\"SUCCESS\".equals(webhookPayload.returnCode())) {", Set.of(), Set.of()));
        assertTrue(webhookAdvice.contains("外部协议"), webhookAdvice);
        assertFalse(webhookAdvice.contains("RunStatus.SUCCESS"), webhookAdvice);

        // 模块反向依赖与跨语言隔离：pilot-core 不能引用 pilot-server 的 Java 类，更不能引用 TS 常量
        String coreAdvice = ConstantAffinityMatcher.advise(internal, MatchContext.of("8080", true,
            "pilot-core/src/main/java/com/pilot/core/HttpClient.java", "int defaultPort = 8080;", Set.of(), Set.of()));
        assertFalse(coreAdvice.contains("ServerConst.PORT"), coreAdvice);
        assertFalse(coreAdvice.contains("WebConst.PORT"), coreAdvice);
    }

    @Test
    void explicitImportBoostsConfidenceForSameCallChain() {
        List<ConstantRef> candidates = List.of(new ConstantRef("SettlementState.COMPLETED",
            "src/main/java/demo/finance/SettlementState.java", "COMPLETED"));

        // 虽然当前文件在 report 包下，但顶部显式 import 了 SettlementState -> 属于同一调用链
        String advice = ConstantAffinityMatcher.advise(candidates,
            MatchContext.of("COMPLETED", false, "src/main/java/demo/report/DailyExporter.java",
                "if (\"COMPLETED\".equals(row.state())) {", Set.of("settlementstate", "settlement"),
                Set.of("demo/finance/SettlementState")));
        assertTrue(advice.contains("SettlementState.COMPLETED"), advice);
        assertTrue(advice.contains("同链路已有可复用定义"), advice);
    }
}
