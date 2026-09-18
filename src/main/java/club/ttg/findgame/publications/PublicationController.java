package club.ttg.findgame.publications;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;
import static club.ttg.findgame.publications.PublicationModels.*;

/** Настройки доступны только ADMIN через существующее правило /api/v1/admin/**. */
@RestController
@RequestMapping({"/api/v1/admin/game-publications", "/api/v1/admin/discord-publications"})
public class PublicationController {
    private final PublicationService service;
    private final GameDigest digest;
    private final PublicationTestSender testSender;
    /** Подключает настройки и предпросмотр. */
    public PublicationController(PublicationService service, GameDigest digest, PublicationTestSender testSender) {
        this.service = service; this.digest = digest; this.testSender = testSender;
    }
    /** Читает настройки и каналы. */
    @GetMapping
    public Overview overview() { return service.overview(); }
    /** Меняет общий график. */
    @PutMapping("/settings")
    public Overview settings(@Valid @RequestBody SettingsInput input) { return service.saveSettings(input); }
    /** Добавляет канал. */
    @PostMapping("/channels")
    public Overview create(@Valid @RequestBody ChannelInput input) { return service.saveChannel(null, input); }
    /** Меняет канал с проверкой версии. */
    @PutMapping("/channels/{channelId}")
    public Overview update(@PathVariable UUID channelId, @Valid @RequestBody ChannelInput input) { return service.saveChannel(channelId, input); }
    /** Удаляет канал, оставляя историю. */
    @DeleteMapping("/channels/{channelId}")
    public Overview delete(@PathVariable UUID channelId, @RequestParam long revision) { return service.deleteChannel(channelId, revision); }
    /** Проверяет сохранённый канал одним сообщением без изменения расписания. */
    @PostMapping("/channels/{channelId}/test")
    public TestResult test(@PathVariable UUID channelId, @RequestParam long revision) { return testSender.send(channelId, revision); }
    /** Показывает текущую подборку без отправки в каналы. */
    @GetMapping("/preview")
    public List<GameEntry> preview() { return digest.preview(); }
    /** Показывает последние результаты. */
    @GetMapping("/history")
    public List<Run> history() { return service.history(); }
}
