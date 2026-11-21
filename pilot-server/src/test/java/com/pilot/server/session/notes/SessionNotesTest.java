package com.pilot.server.session.notes;

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
    void noteStartsInInboxAndStatusSurvivesEditingAndReopen() {
        SessionNotes first = open("status.db");
        SessionNotes.Note note = first.create("s1", "顺手想到的点子");
        assertEquals(SessionNotes.INBOX, note.status(), "新想法默认在收件箱，还没安排");
        assertEquals(null, note.link());

        SessionNotes.Note planned =
            first.setStatus("s1", note.id(), SessionNotes.PLANNED, new SessionNotes.Link("session", "s1"));
        assertEquals(SessionNotes.PLANNED, planned.status());
        assertEquals("session", planned.link().kind());

        // 改正文不能把状态和关联目标改回收件箱。
        SessionNotes.Note edited = first.update("s1", note.id(), "顺手想到的点子（补充）");
        assertEquals(SessionNotes.PLANNED, edited.status());
        assertEquals("s1", edited.link().id());

        SessionNotes reopened = open("status.db");
        assertEquals(SessionNotes.PLANNED, reopened.list("s1").getFirst().status());
    }

    @Test
    void promoteComposesTextWithSourceQuoteAndMarksPlanned() {
        SessionNotes notes = open("promote.db");
        SessionNotes.Note note =
            notes.create("s1", "把这个想法做成任务", new SessionNotes.SourceInput(7, "选中并记下来的原文", "assistant"));

        SessionNotes.Promotion promotion = notes.promote("s1", note.id(), new SessionNotes.Link("queue", "s1"));
        assertTrue(promotion.text().startsWith("把这个想法做成任务"), promotion.text());
        assertTrue(promotion.text().contains("第 7 条"), "转成任务后仍要能看出这句是从哪条对话来的");
        assertTrue(promotion.text().contains("> 选中并记下来的原文"), "引用原文要保留，否则丢掉当初的上下文");
        assertTrue(promotion.text().contains("助手回复"));
        assertEquals(SessionNotes.PLANNED, promotion.note().status());
        assertEquals("queue", promotion.note().link().kind());
        // 正文本身不被改写：想法是原话，组装只发生在提交用的文本里。
        assertEquals("把这个想法做成任务", notes.list("s1").getFirst().content());
    }

    @Test
    void promoteWithoutSourceStillProducesCleanText() {
        SessionNotes notes = open("promote2.db");
        SessionNotes.Note note = notes.create("s1", "没有来源的想法");
        assertEquals("没有来源的想法", notes.promote("s1", note.id(), null).text());
    }

    @Test
    void unknownStatusAndMissingNoteAreRejected() {
        SessionNotes notes = open("status2.db");
        SessionNotes.Note note = notes.create("s1", "想法");
        assertThrows(IllegalArgumentException.class, () -> notes.setStatus("s1", note.id(), "archived", null));
        assertThrows(IllegalArgumentException.class, () -> notes.setStatus("s1", "note_missing", "done", null));
        // 不做隐式恢复：被删除的想法不会因为一次转任务而回来。
        notes.delete("s1", note.id());
        assertThrows(IllegalArgumentException.class, () -> notes.promote("s1", note.id(), null));
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
    void searchMatchesOnlyGivenSessionsAndFollowsEditsAndDeletes() {
        SessionNotes notes = new SessionNotes((SqliteStore)null);
        var first = notes.create("s1", "补一条登录测试，覆盖失败路径");
        notes.create("s2", "登录页面文案检查");
        notes.create("s3", "别的项目的登录记录");
        var refs = List.of(new SessionNotes.SessionRef("s1", "会话一"), new SessionNotes.SessionRef("s2", "会话二"));

        List<SessionNotes.NoteHit> hits = notes.search(refs, "登录");
        assertEquals(2, hits.size());
        assertEquals("s1", hits.get(0).sessionId());
        assertEquals("会话一", hits.get(0).sessionTitle());
        assertTrue(hits.get(0).snippet().contains("登录"), hits.get(0).snippet());
        assertTrue(notes.search(refs, "  ").isEmpty(), "空关键词不搜索");
        assertTrue(notes.search(refs, "不存在的词").isEmpty());

        notes.update("s1", first.id(), "改成并发测试");
        assertTrue(notes.search(refs, "登录").stream().noneMatch(hit -> hit.sessionId().equals("s1")));
        assertEquals(1, notes.search(refs, "并发").size());

        notes.delete("s1", first.id());
        assertTrue(notes.search(refs, "并发").isEmpty(), "删除后不再出现在结果里");
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
