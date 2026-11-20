package com.pilot.core.tool.code;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.core.workspace.PathValidator;

class FileOutlineTest {
    @TempDir
    Path workspace;

    @Test
    void extractsJavaClassAndMethodsWhileIgnoringControlFlow() throws Exception {
        Files.writeString(workspace.resolve("Sample.java"), """
            package demo;

            public class Sample {
                private int count = 0;

                public Sample(int initial) {
                    this.count = initial;
                }

                public String handle(String input) {
                    if (input == null) {
                        return "";
                    }
                    for (int i = 0; i < 3; i++) {
                        count++;
                    }
                    return input;
                }

                private static int score(
                    String text,
                    int weight) {
                    return text.length() * weight;
                }
            }
            """);

        CodeSymbolTool tool = new CodeSymbolTool(new PathValidator(workspace));
        assertTrue(tool.parallelSafe(), "code_symbol 必须支持并行只读，不能打断批读");
        assertTrue(tool.availability().available(), "无外部编译器时仍应可用");

        String out = tool.execute(Map.of("path", "Sample.java"));
        assertTrue(out.startsWith("Sample.java（共 "), out);
        assertTrue(out.contains("L3: public class Sample"), out);
        assertTrue(out.contains("L6: public Sample(int initial)"), out);
        assertTrue(out.contains("L10: public String handle(String input)"), out);
        assertTrue(out.contains("L20: private static int score"), out);
        assertFalse(out.contains("if (input"), out);
        assertFalse(out.contains("for (int i"), out);
    }

    @Test
    void extractsTypeScriptPythonGoRustAndMarkdownSymbols() throws Exception {
        Files.writeString(workspace.resolve("app.ts"), """
            export interface Item { id: string }
            export async function fetchItem(id: string) {
                return id;
            }
            export const formatItem = (x: Item) => x.id;
            class Store {
                private save(item: Item) {
                    if (item) { return; }
                }
            }
            """);
        Files.writeString(workspace.resolve("worker.py"), """
            class Worker:
                async def run(self, job):
                    return job
            def helper():
                pass
            """);
        Files.writeString(workspace.resolve("main.go"), """
            type Server struct{}
            func (s *Server) Start() {}
            func main() {}
            """);
        Files.writeString(workspace.resolve("lib.rs"), """
            pub struct Engine {}
            impl Engine {
                pub async fn tick(&self) {}
            }
            """);
        Files.writeString(workspace.resolve("guide.md"), """
            # Title
            ```java
            # not a heading
            ```
            ## Section Two
            """);

        CodeSymbolTool tool = new CodeSymbolTool(new PathValidator(workspace));

        String ts = tool.execute(Map.of("path", "app.ts"));
        assertTrue(ts.startsWith("app.ts（共 "), ts);
        assertTrue(ts.contains("L1: export interface Item"), ts);
        assertTrue(ts.contains("L2: export async function fetchItem"), ts);
        assertTrue(ts.contains("L5: export const formatItem"), ts);
        assertTrue(ts.contains("L7: private save(item: Item)"), ts);
        assertFalse(ts.contains("if (item)"), ts);

        String py = tool.execute(Map.of("path", "worker.py"));
        assertTrue(py.startsWith("worker.py（共 "), py);
        assertTrue(py.contains("L1: class Worker:"), py);
        assertTrue(py.contains("L2: async def run(self, job):"), py);
        assertTrue(py.contains("L4: def helper():"), py);

        String go = tool.execute(Map.of("path", "main.go"));
        assertTrue(go.startsWith("main.go（共 "), go);
        assertTrue(go.contains("L1: type Server struct"), go);
        assertTrue(go.contains("L2: func (s *Server) Start()"), go);
        assertTrue(go.contains("L3: func main()"), go);

        String rs = tool.execute(Map.of("path", "lib.rs"));
        assertTrue(rs.startsWith("lib.rs（共 "), rs);
        assertTrue(rs.contains("L1: pub struct Engine"), rs);
        assertTrue(rs.contains("L3: pub async fn tick(&self)"), rs);

        String md = tool.execute(Map.of("path", "guide.md"));
        assertTrue(md.startsWith("guide.md（共 "), md);
        assertTrue(md.contains("L1: # Title"), md);
        assertTrue(md.contains("L5: ## Section Two"), md);
        assertFalse(md.contains("not a heading"), md);
    }

    @Test
    void enforcesPathBoundaryAndSkipsBinaryFiles() throws Exception {
        Files.write(workspace.resolve("img.bin"), new byte[] {1, 0, 2, 0});
        CodeSymbolTool tool = new CodeSymbolTool(new PathValidator(workspace));

        assertTrue(tool.execute(Map.of("path", "../outside.java")).startsWith("ERROR:"));
        assertTrue(tool.execute(Map.of("path", "img.bin")).startsWith("ERROR: 二进制文件"));
    }
}
