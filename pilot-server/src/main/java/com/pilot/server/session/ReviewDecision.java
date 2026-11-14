package com.pilot.server.session;

import java.util.Arrays;
import java.util.List;

import com.pilot.core.util.WireEnum;

/** 目标核验器返回的结论。名字就是核验工具 schema 里 decision 的取值，顺序也按 schema 给模型的顺序。 */
enum ReviewDecision implements WireEnum {
    COMPLETE, CONTINUE, WAIT, NEED_INPUT, BLOCKED, IMPOSSIBLE;

    /** 给 schema 的 enum 列表。 */
    static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 这些结论会让任务停下，并原样记为执行阶段。 */
    static boolean stops(String decision) {
        return WireEnum.in(decision, WAIT, NEED_INPUT, BLOCKED, IMPOSSIBLE);
    }
}
