package com.pilot.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FileNamesTest {

    @Test
    void extensionIsLowerCaseWithoutDot() {
        assertEquals("pdf", FileNames.extension("report.PDF"));
        assertEquals("gz", FileNames.extension("archive.tar.gz"));
        assertEquals("", FileNames.extension("Makefile"));
        assertEquals("", FileNames.extension("trailing."));
        assertEquals("gitignore", FileNames.extension(".gitignore"));
    }

    @Test
    void dotExtensionKeepsTheDot() {
        assertEquals(".xlsx", FileNames.dotExtension("报表.XLSX"));
        assertEquals(".gz", FileNames.dotExtension("archive.tar.gz"));
        assertEquals("", FileNames.dotExtension("Makefile"));
        assertEquals(".", FileNames.dotExtension("trailing."));
    }
}
