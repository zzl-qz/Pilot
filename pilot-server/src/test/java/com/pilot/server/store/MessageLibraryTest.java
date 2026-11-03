package com.pilot.server.store;
import com.pilot.core.event.*;
import com.pilot.core.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MessageLibraryTest {
    @TempDir Path root;SqliteStore store;SessionService sessions;MessageLibrary library;String id;
    @BeforeEach void setup(){store=new SqliteStore(root.resolve("library.db"));sessions=new SessionService(Clock.systemUTC(),new JdbcSessionRepository(store,new EventCodec(JsonMapper.builder().build())));library=new MessageLibrary(store,sessions);id=sessions.createSession("跨平台检查").id();}
    @Test void chineseShortTermsLiteralSyntaxAndAssistantTextAreSearchableWithAnchors(){
        sessions.publish(id,"r",new RunStarted("检查恢复调度"));sessions.publish(id,"r",new AssistantDelta("已经修复中文目录，保留 C:\\work\\file 和 a*b 引号\"示例"));
        var hit=library.search("中文目录",null,false,0).items().getFirst();assertEquals(2,hit.sequence());assertEquals(1,hit.anchor());assertEquals("assistant",hit.role());
        assertEquals(1,library.search("中文",null,false,0).items().size());assertEquals(1,library.search("a*b",null,false,0).items().size());
        assertEquals(1,library.search("引号\"示",null,false,0).items().size());assertTrue(library.search("OR NOT",null,false,0).items().isEmpty());
        String side=sessions.createSession("侧边",null,null,false,id).id();sessions.publish(side,"side",new RunStarted("中文目录"));assertEquals(1,library.search("中文目录",null,false,0).items().size());
    }
    @Test void bookmarksPersistMigrateIdempotentlyAndNeverResurrectDeletedItems(){
        sessions.publish(id,"r",new RunStarted("书签消息"));var imported=library.migrate(id,List.of(1L,999L));assertEquals(List.of(999L),imported.skipped());
        assertEquals(1,library.bookmarks(id).size());assertEquals(1,library.search("",null,true,0).items().size());
        var reopened=new MessageLibrary(new SqliteStore(root.resolve("library.db")),sessions);assertEquals(1,reopened.bookmarks(id).size());
        reopened.delete(id,1);reopened.migrate(id,List.of(1L,999L));assertTrue(reopened.bookmarks(id).isEmpty());
        assertThrows(IllegalArgumentException.class,()->library.save(id,300));
        library.save(id,1);sessions.delete(id);assertTrue(library.search("书签",null,false,0).items().isEmpty());
    }
    @Test void indexBackfillsExistingEventsAndDeletesAndPaginationStayConsistent() throws Exception {
        for(int i=0;i<55;i++)sessions.publish(id,"r"+i,new RunStarted("历史关键词 "+i));
        var page=library.search("历史关键词",id,false,0);assertEquals(50,page.items().size());assertTrue(page.hasMore());
        assertEquals(5,library.search("历史关键词",id,false,page.nextOffset()).items().size());
        try(var c=store.open();var s=c.createStatement()){s.execute("DELETE FROM message_search");s.execute("DELETE FROM schema_meta WHERE key='message_search_v1'");}
        new SqliteStore(root.resolve("library.db"));assertEquals(50,library.search("历史关键词",id,false,0).items().size());
        try(var c=store.open();var s=c.prepareStatement("DELETE FROM events WHERE session_id=? AND sequence>50")){s.setString(1,id);s.executeUpdate();}
        assertFalse(library.search("历史关键词",id,false,0).hasMore());
    }
}
