package com.pilot.core.model;

import java.util.Locale;

/** 仅可确认未受理的只读原生工具拒绝允许降级；鉴权、限流、断流不重放。 */
public final class NativeToolRejectedException extends ModelClientException {
    private final boolean protocolRejected;

    public NativeToolRejectedException(String message) {
        this(message, false);
    }

    public NativeToolRejectedException(String message, boolean protocolRejected) {
        super(ModelFailureKind.PERMANENT, message);
        this.protocolRejected = protocolRejected;
    }

    public boolean protocolRejected() {
        return protocolRejected;
    }

    public static boolean rejected(int status, String body) {
        String detail = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (ModelFailureSignals.billingOrQuota(detail)) {
            return false;
        }
        if (status == 405) {
            return true;
        }
        if (status == 404) {
            return !detail.contains("model") && (detail.contains("not found") || detail.contains("route"));
        }
        if (status != 400 && status != 422) {
            return false;
        }
        return (detail.contains("web_search") || detail.contains("web_fetch") || detail.contains("server tool"))
            && (detail.contains("unsupported") || detail.contains("not support") || detail.contains("unknown")
                || detail.contains("not allowed") || detail.contains("invalid tool"));
    }
}
