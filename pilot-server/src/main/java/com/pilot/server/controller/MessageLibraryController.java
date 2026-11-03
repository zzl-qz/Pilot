package com.pilot.server.controller;
import com.pilot.server.store.MessageLibrary;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;
@RestController @RequestMapping("/api/library")
public class MessageLibraryController {
    private final MessageLibrary library;
    public MessageLibraryController(MessageLibrary library){this.library=library;}
    @GetMapping("/search") public Object search(@RequestParam(defaultValue="") String q,@RequestParam(required=false)String sessionId,@RequestParam(defaultValue="false")boolean saved,@RequestParam(defaultValue="0")int offset){return library.search(q,sessionId,saved,offset);}
    @GetMapping("/{session}/bookmarks") public Object list(@PathVariable String session){return library.bookmarks(session);}
    @PutMapping("/{session}/bookmarks/{sequence}") public Object put(@PathVariable String session,@PathVariable long sequence){return library.save(session,sequence);}
    @DeleteMapping("/{session}/bookmarks/{sequence}") public void delete(@PathVariable String session,@PathVariable long sequence){library.delete(session,sequence);}
    public record Migration(List<Long> sequences){}
    @PostMapping("/{session}/bookmarks/import") public Object migrate(@PathVariable String session,@RequestBody Migration input){return library.migrate(session,input.sequences());}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> bad(RuntimeException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
}
