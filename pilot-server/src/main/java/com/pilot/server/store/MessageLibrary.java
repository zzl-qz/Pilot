package com.pilot.server.store;

import com.pilot.core.event.*;
import com.pilot.core.session.SessionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** FTS5 trigram 覆盖中文子串；不足三个字符用字面量扫描，结果始终按原事件定位。 */
@Service
public class MessageLibrary {
    public record Bookmark(long sequence,String title,String preview,String key) { }
    public record Hit(String sessionId,String title,long sequence,long anchor,String role,String text,boolean bookmarked) { }
    public record Page(List<Hit> items,boolean hasMore,int nextOffset) { }
    private final SqliteStore store;
    private final SessionService sessions;
    private final Map<String,Map<Long,Bookmark>> memory=new HashMap<>();
    private final Set<String> imports=new HashSet<>();
    @org.springframework.beans.factory.annotation.Autowired
    public MessageLibrary(ObjectProvider<SqliteStore> provider,SessionService sessions){this(provider.getIfAvailable(),sessions);}
    public MessageLibrary(SqliteStore store,SessionService sessions){this.store=store;this.sessions=sessions;}
    public synchronized List<Bookmark> bookmarks(String session){
        require(session);
        if(store==null)return memory.getOrDefault(session,Map.of()).values().stream().filter(b->event(session,b.sequence())!=null).sorted(Comparator.comparingLong(Bookmark::sequence)).toList();
        return JdbcSupport.query(store,"SELECT b.* FROM message_bookmarks b JOIN events e ON e.session_id=b.session_id AND e.sequence=b.sequence WHERE b.session_id=? ORDER BY b.sequence",q->q.setString(1,session),
                r->new Bookmark(r.getLong("sequence"),r.getString("title"),r.getString("preview"),""),"读取书签失败");
    }
    public synchronized Bookmark save(String session,long sequence){
        require(session);if(sequence<1)throw new IllegalArgumentException("消息序号无效");var event=event(session,sequence);
        if(event==null || !Set.of("run.started","run.steered","assistant.delta").contains(event.type()))throw new IllegalArgumentException("消息不存在或不能收藏");
        var old=bookmarks(session);if(old.size()>=500 && old.stream().noneMatch(b->b.sequence()==sequence))throw new IllegalArgumentException("每个对话最多 500 个书签");
        String text=text(event);String title=text.lines().filter(line->!line.isBlank()).findFirst().orElse("附件消息");
        var b=new Bookmark(sequence,clip(title,100),clip(text,350),"");
        if(store==null)memory.computeIfAbsent(session,k->new LinkedHashMap<>()).put(sequence,b);
        else JdbcSupport.update(store,"INSERT INTO message_bookmarks(session_id,sequence,title,preview,created_at) VALUES(?,?,?,?,?) ON CONFLICT(session_id,sequence) DO NOTHING",q->{q.setString(1,session);q.setLong(2,sequence);q.setString(3,b.title());q.setString(4,b.preview());q.setString(5,Instant.now().toString());},"保存书签失败");
        return b;
    }
    public synchronized void delete(String session,long sequence){
        require(session);
        if(store==null){memory.getOrDefault(session,new HashMap<>()).remove(sequence);return;}
        JdbcSupport.update(store,"DELETE FROM message_bookmarks WHERE session_id=? AND sequence=?",q->{q.setString(1,session);q.setLong(2,sequence);},"删除书签失败");
    }
    public record ImportResult(List<Bookmark> bookmarks,List<Long> skipped){}
    public synchronized ImportResult migrate(String session,List<Long> sequences){
        if(sequences==null || sequences.size()>500)throw new IllegalArgumentException("最多迁移 500 个书签");
        require(session);
        String fingerprint=UUID.nameUUIDFromBytes(sequences.stream().filter(Objects::nonNull).distinct().sorted().toList().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        boolean imported=store==null?imports.contains(session+fingerprint):JdbcSupport.queryOne(store,"SELECT fingerprint FROM bookmark_imports WHERE session_id=? AND fingerprint=?",q->{q.setString(1,session);q.setString(2,fingerprint);},r->r.getString(1),"读取书签迁移状态失败").isPresent();
        if(imported)return new ImportResult(bookmarks(session),List.of());
        List<Long> skipped=new ArrayList<>();
        for(Long seq:sequences){if(seq==null || seq<1){if(seq!=null)skipped.add(seq);continue;}try{save(session,seq);}catch(IllegalArgumentException e){skipped.add(seq);}}
        if(store==null)imports.add(session+fingerprint);
        else JdbcSupport.update(store,"INSERT OR IGNORE INTO bookmark_imports(session_id,fingerprint) VALUES(?,?)",q->{q.setString(1,session);q.setString(2,fingerprint);},"保存书签迁移状态失败");
        return new ImportResult(bookmarks(session),List.copyOf(skipped));
    }
    public Page search(String query,String session,boolean saved,int offset){
        String term=query==null?"":query.strip();if(term.length()>200 || offset<0 || offset>100000)throw new IllegalArgumentException("搜索条件过长或页码无效");
        if(term.isEmpty() && !saved)return new Page(List.of(),false,0);
        if(session!=null && !session.isBlank())require(session);
        List<Hit> rows;
        if(store==null){
            List<Hit> all=new ArrayList<>();
            for(var s:sessions.list()){
                if(s.isSide() || (session!=null && !session.isBlank() && !session.equals(s.id())))continue;
                var bookmarks=bookmarks(s.id()).stream().map(Bookmark::sequence).collect(java.util.stream.Collectors.toSet());long anchor=0;
                for(var e:sessions.events(s.id())){
                    if(Set.of("run.started","run.steered").contains(e.type()))anchor=e.sequence();
                    if(!Set.of("run.started","run.steered","assistant.delta").contains(e.type()))continue;
                    String content=text(e);boolean marked=bookmarks.contains(e.sequence());
                    if((!saved||marked) && content.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT)))all.add(new Hit(s.id(),s.title(),e.sequence(),anchor==0?e.sequence():anchor,e.type().equals("assistant.delta")?"assistant":"user",snippet(content,term),marked));
                }
            }
            rows=all.stream().sorted(Comparator.comparing(Hit::sessionId).thenComparing(Comparator.comparingLong(Hit::sequence).reversed())).skip(offset).limit(51).toList();
        }else{
            // MATCH 仅处理转义后的字面短语，避免用户的引号、星号或布尔词变成查询语法。
            boolean fts=term.codePointCount(0,term.length())>=3;
            String sql="SELECT m.session_id,m.sequence,m.text,s.title,e.type,b.sequence AS saved, "
                    +"COALESCE((SELECT MAX(u.sequence) FROM events u WHERE u.session_id=m.session_id AND u.sequence<=m.sequence AND u.type IN ('run.started','run.steered')),m.sequence) AS anchor "
                    +"FROM message_search m JOIN sessions s ON s.id=m.session_id JOIN events e ON e.session_id=m.session_id AND e.sequence=m.sequence LEFT JOIN message_bookmarks b ON b.session_id=m.session_id AND b.sequence=m.sequence "
                    +"WHERE (s.parent_session_id IS NULL OR s.parent_session_id='') "
                    +(fts?"AND message_search MATCH ? ":"AND instr(lower(m.text),lower(?))>0 ")
                    +((session!=null && !session.isBlank())?"AND m.session_id=? ":"")+(saved?"AND b.sequence IS NOT NULL ":"")
                    +"ORDER BY e.created_at DESC,m.session_id,m.sequence DESC LIMIT 51 OFFSET ?";
            rows=JdbcSupport.query(store,sql,q->{int i=1;q.setString(i++,fts?'"'+term.replace("\"","\"\"")+'"':term);if(session!=null&&!session.isBlank())q.setString(i++,session);q.setInt(i,offset);},
                    r->new Hit(r.getString("session_id"),r.getString("title"),r.getLong("sequence"),r.getLong("anchor"),r.getString("type").equals("assistant.delta")?"assistant":"user",snippet(r.getString("text"),term),r.getObject("saved")!=null),"搜索消息失败");
        }
        return new Page(rows.stream().limit(50).toList(),rows.size()>50,offset+Math.min(rows.size(),50));
    }
    private void require(String id){if(sessions.find(id).isEmpty())throw new IllegalArgumentException("对话不存在");}
    private EventEnvelope event(String id,long sequence){return sessions.eventsPageAfter(id,sequence-1,1).stream().filter(e->e.sequence()==sequence).findFirst().orElse(null);}
    private static String text(EventEnvelope e){return Objects.toString(e.payload() instanceof RunStarted s?s.input():e.payload() instanceof RunSteered s?s.input():e.payload() instanceof AssistantDelta d?d.text():"", "");}
    private static String clip(String value,int limit){return value.substring(0,Math.min(value.length(),limit));}
    private static String snippet(String value,String term){int at=value.toLowerCase(Locale.ROOT).indexOf(term.toLowerCase(Locale.ROOT));int start=Math.max(0,at-70);return(start>0?"…":"")+clip(value.substring(start),300)+(value.length()>start+300?"…":"");}
}
