package com.pilot.server.voice;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {
    private final VoiceService voice;
    public VoiceController(VoiceService voice) { this.voice = voice; }
    @GetMapping("/settings") public VoiceService.Settings settings() { return voice.settings(); }
    @PutMapping("/settings") public VoiceService.Settings update(@RequestBody VoiceService.Update update) {
        try { return voice.update(update); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }
    @PostMapping(value = "/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> transcribe(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > VoiceService.MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "录音不能超过 10 MB");
        try { return Map.of("text", voice.transcribe(file.getBytes(), file.getContentType())); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        catch (VoiceService.VoiceException e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage()); }
    }
}
