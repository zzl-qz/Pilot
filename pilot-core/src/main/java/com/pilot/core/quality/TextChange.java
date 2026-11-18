package com.pilot.core.quality;

/** 一个文件在本轮修改前、修改后的文本。供收尾审查对照 diff。 */
public record TextChange(String before, String after) {
}
