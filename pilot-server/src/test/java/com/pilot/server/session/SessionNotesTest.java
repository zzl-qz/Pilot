package com.pilot.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.pilot.server.store.SqliteStore;

/** 想法只是用户自己的记事：按会话落盘、可改可删，不产生任何事件。 */
class SessionNotesTest {
    @TempDir
    Path root;

    private SessionNotes open(String file) {
        return new SessionNotes(new SqliteStore(root.resolve(file)));
    }

    private static List<String> ids(List<SessionNotes.Note> notes) {
        return notes.stream().map(SessionNotes.Note::id).toList();
    }

    @Test
    void notesSurviveReopenAndDeletedStayDeleted() {
        SessionNotes first = open("notes.db");
        SessionNotes.Note older = first.create("s1", "第一条想法\n第二行也保留");
        SessionNotes.Note newer = first.create("s1", "第二条想法");
        assertEquals(List.of(newer.id(), older.id()), ids(first.list("s1")), "新想法排在最前");

        SessionNotes.Note edited = first.update("s1", older.id(), "改过的想法");
        first.delete("s1", newer.id());
        assertEquals("改过的想法", edited.content());

        // 关掉再打开（等价于刷新页面或重启应用）：编辑结果还在，删掉的不会恢复
        SessionNotes reopened = open("notes.db");
        List<SessionNotes.Note> notes = reopened.list("s1");
        assertEquals(1, notes.size());
        assertEquals(older.id(), notes.get(0).id());
        assertEquals("改过的想法", notes.get(0).content());
        assertEquals(older.createdAt(), notes.get(0).createdAt());
        assertFalse(notes.get(0).updatedAt().isBefore(older.updatedAt()));
        reopened.delete("s1", newer.id());
        assertEquals(1, reopened.list("s1").size(), "重复删除不能报错");
    }

    @Test
    void sourceSurvivesReopenAndKeepsExcerptWhenHistoryIsGone() {
        SessionNotes first = open("source.db");
        SessionNotes.Note note = first.create("s1", "补充说明", new SessionNotes.SourceInput(7, "选中的原文", "assistant"));
        assertEquals(7, note.source().sequence());
        assertEquals("选中的原文", note.source().excerpt());
        assertFalse(note.source().available(), "没有事件服务时无法确认来源，标为不可用但不丢原文");

        // 编辑正文不改来源
        first.update("s1", note.id(), "补充说明（已改）");

        SessionNotes reopened = open("source.db");
        var saved = reopened.list("s1").get(0);
        assertEquals("补充说明（已改）", saved.content());
        assertEquals(7, saved.source().sequence());
        assertEquals("选中的原文", saved.source().excerpt());
        assertEquals("assistant", saved.source().role());
    }

    @Test
    void notesAreIsolatedPerSessionAndSessionDeletionClearsThem() {
        SessionNotes notes = open("isolation.db");
        notes.create("s1", "属于 s1");
        notes.create("s2", "属于 s2");

        assertEquals(List.of("属于 s1"), notes.list("s1").stream().map(SessionNotes.Note::content).toList());
        assertEquals(List.of("属于 s2"), notes.list("s2").stream().map(SessionNotes.Note::content).toList());

        notes.deleteBySession("s1");
        assertTrue(notes.list("s1").isEmpty());
        assertEquals(1, notes.list("s2").size(), "删除会话不能带走别的会话");
    }

    @Test
    void contentIsNotTrimmedButBlankAndOversizedAreRejected() {
        SessionNotes notes = new SessionNotes((SqliteStore)null);
        SessionNotes.Note note = notes.create("s", "  保留两端的空格  ");
        assertEquals("  保留两端的空格  ", note.content(), "带回输入框的应当是原文");
        assertThrows(IllegalArgumentException.class, () -> notes.create("s", "   "));
        assertThrows(IllegalArgumentException.class, () -> notes.create("s", "字".repeat(SessionNotes.MAX_CONTENT + 1)));
        assertThrows(IllegalArgumentException.class,
            () -> notes.update("s", note.id(), "字".repeat(SessionNotes.MAX_CONTENT + 1)));
        assertThrows(IllegalArgumentException.class, () -> notes.update("s", "missing", "不存在"));
    }

    @Test
    void memoryModeKeepsOrderAndCapsNoteCount() {
        SessionNotes notes = new SessionNotes((SqliteStore)null);
        for (int index = 0; index < SessionNotes.MAX_NOTES; index++) {
            notes.create("s", "想法 " + index);
        }
        assertEquals("想法 199", notes.list("s").get(0).content());
        assertThrows(IllegalArgumentException.class, () -> notes.create("s", "超出上限"));
    }
}
