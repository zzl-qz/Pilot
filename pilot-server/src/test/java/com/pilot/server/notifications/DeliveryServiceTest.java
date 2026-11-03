package com.pilot.server.notifications;
import com.pilot.server.results.ResultInboxService;
import com.pilot.server.store.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DeliveryServiceTest {
    @TempDir Path root;final MutableClock clock=new MutableClock();ResultInboxService results;AutomationStore store;DeliveryService service;AtomicInteger status=new AtomicInteger(503);List<String> keys=new ArrayList<>();
    @BeforeEach void setup(){store=new AutomationStore(new SqliteStore(root.resolve("outbox.db")));results=mock(ResultInboxService.class);when(results.deliveryItems(any(Instant.class))).thenReturn(List.of());service=make(store);}
    DeliveryService make(AutomationStore store){return new DeliveryService(store,JsonMapper.builder().build(),results,clock,(target,id,payload)->{keys.add(id);return status.get();});}
    @AfterEach void close(){service.close();}
    ResultInboxService.Item item(Instant at){return new ResultInboxService.Item("run-a","v1","session","维护完成","CHAT","COMPLETED","测试通过",at,"run-a",null,false,false,List.of(),List.of(),List.of(),null);}
    @Test void persistentOutboxDeduplicatesAndRetriesOnlyDeliveryWithStableKey(){
        service.configure(0,true,"https://example.test/hook");clock.at=clock.at.plusSeconds(1);when(results.deliveryItems(any(Instant.class))).thenReturn(List.of(item(clock.instant())));
        service.collect();service.collect();assertEquals(2,service.list().size());
        var first=service.claim("WEBHOOK");assertNull(service.claim("WEBHOOK"));service.send(first);assertEquals("RETRY",service.get(first.id()).state());
        var persisted=new AutomationStore(new SqliteStore(root.resolve("outbox.db")));service.close();service=make(persisted);clock.at=service.get(first.id()).nextAt();
        var second=service.claim("WEBHOOK");assertNotEquals(first.lease(),second.lease());assertThrows(IllegalStateException.class,()->service.receipt(first.id(),new DeliveryService.Receipt(first.lease(),true,"")));
        status.set(204);service.send(second);assertEquals("DELIVERED",service.get(first.id()).state());assertEquals(List.of(first.id(),first.id()),keys);
        service.collect();assertNull(service.claim("WEBHOOK"));
    }
    @Test void expiredLeaseRetriesAndAttemptLimitRequiresExplicitRetry(){
        service.configure(0,true,"");when(results.deliveryItems(any(Instant.class))).thenReturn(List.of(item(clock.instant())));service.collect();
        String id=null;
        for(int i=0;i<6;i++){var d=service.claim("DESKTOP");assertNotNull(d);id=d.id();clock.at=d.nextAt().plusSeconds(1);}
        assertNull(service.claim("DESKTOP"));var failed=service.get(id);assertEquals("FAILED",failed.state());
        service.retry(id,failed.version());assertNotNull(service.claim("DESKTOP"));
        assertThrows(IllegalStateException.class,()->service.retry(failed.id(),failed.version()));
    }
    @Test void enablingDoesNotFloodOldResultsAndDisabledOrChangedChannelsDoNotSend(){
        when(results.deliveryItems(any(Instant.class))).thenReturn(List.of(item(clock.instant().minusSeconds(1))));var settings=service.configure(0,false,"https://example.test/hook");service.collect();assertTrue(service.list().isEmpty());
        when(results.deliveryItems(any(Instant.class))).thenReturn(List.of(item(clock.instant())));service.collect();service.configure(settings.version(),false,"https://example.test/new");assertNull(service.claim("WEBHOOK"));assertEquals("CANCELLED",service.list().getFirst().state());assertTrue(keys.isEmpty());
        assertThrows(IllegalArgumentException.class,()->service.configure(2,false,"file:///etc/passwd"));
    }
    @Test void realHttpSenderPreservesKeyAndPayloadAndDoesNotFollowRedirects() throws Exception {
        var receiver=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var bodies=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var headers=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var redirected=new AtomicInteger();var response=new AtomicInteger(302);
        receiver.createContext("/hook",exchange->{
            headers.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Location","/other");exchange.sendResponseHeaders(response.get(),-1);exchange.close();
        });
        receiver.createContext("/other",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});
        receiver.start();service.close();service=new DeliveryService(store,JsonMapper.builder().build(),results);
        try {
            service.configure(0,false,"http://127.0.0.1:"+receiver.getAddress().getPort()+"/hook");
            when(results.deliveryItems(any(Instant.class))).thenReturn(List.of(item(Instant.now())));service.collect();
            var first=service.claim("WEBHOOK");assertNotNull(first);service.send(first);
            var retry=service.get(first.id());assertEquals("RETRY",retry.state());assertEquals(0,redirected.get());
            service.retry(retry.id(),retry.version());response.set(204);service.send(service.claim("WEBHOOK"));
            assertEquals("DELIVERED",service.get(first.id()).state());assertEquals(List.of(first.id(),first.id()),headers);
            var body=JsonMapper.builder().build().readTree(bodies.getFirst());assertEquals("run-a",body.get("resultId").asText());assertEquals("测试通过",body.get("summary").asText());
        } finally { receiver.stop(0); }
    }
    static class MutableClock extends Clock{Instant at=Instant.parse("2026-11-03T00:00:00Z");public Instant instant(){return at;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}}
}
