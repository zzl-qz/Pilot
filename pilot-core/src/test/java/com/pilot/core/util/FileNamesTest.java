package com.pilot.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

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

    @Test
    void pathAndStemIgnoreDotsInDirectories() {
        assertEquals("", FileNames.extension("dir.v1/Makefile"));
        assertEquals("", FileNames.extension("dir.v1\\Makefile"));
        assertEquals("pdf", FileNames.extension(Path.of("dir.v1", "report.PDF")));
        assertEquals(".pdf", FileNames.dotExtension(Path.of("dir.v1", "report.PDF")));
        assertEquals("report", FileNames.stem("dir.v1/report.PDF"));
        assertEquals("Makefile", FileNames.stem("dir.v1\\Makefile"));
        assertEquals("archive.tar", FileNames.stem("archive.tar.gz"));
        assertEquals("", FileNames.extension(Path.of("/")));
        assertEquals("", FileNames.dotExtension(Path.of("/")));
        assertEquals("", FileNames.extension((Path)null));
        assertEquals("", FileNames.dotExtension((Path)null));
    }
}
