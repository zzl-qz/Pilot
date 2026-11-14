package com.pilot.core.util;

import java.util.Optional;

/**
 * 落盘和接口里用字符串表示的状态。
 * 枚举名就是协议值，改名等于改协议。
 * 记录字段仍保留字符串，旧版本写入的未知值照原样读写，只是不等于任何枚举项。
 */
public interface WireEnum {
    /** 由枚举自带，值即协议字符串。 */
    String name();

    /** 和协议字符串比较；null 与未知值都不相等。 */
    default boolean is(String value) {
        return name().equals(value);
    }

    /** value 是否为候选之一。 */
    static boolean in(String value, WireEnum... candidates) {
        for (WireEnum candidate : candidates) {
            if (candidate.is(value)) {
                return true;
            }
        }
        return false;
    }

    /** 协议字符串转枚举；null 与未知值返回空，不抛异常。 */
    static <E extends Enum<E> & WireEnum> Optional<E> parse(Class<E> type, String value) {
        for (E candidate : type.getEnumConstants()) {
            if (candidate.is(value)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
