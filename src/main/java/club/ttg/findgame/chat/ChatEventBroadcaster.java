package club.ttg.findgame.chat;

import club.ttg.findgame.chat.api.ChatEventResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
class ChatEventBroadcaster {

    private static final long SSE_TIMEOUT_MILLIS = 30L * 60L * 1000L;

    private final Map<ChatRoom, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    SseEmitter subscribe(ChatRoom room) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        CopyOnWriteArrayList<SseEmitter> roomSubscribers =
                subscribers.computeIfAbsent(room, ignored -> new CopyOnWriteArrayList<>());
        roomSubscribers.add(emitter);

        Runnable remove = () -> remove(room, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());

        try {
            emitter.send(SseEmitter.event().name("ready").data(Map.of("connected", true)));
        } catch (IOException exception) {
            remove.run();
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    void broadcast(ChatEventResponse event) {
        send(new ChatRoom(event.nexusId()), "chat-event", event.id().toString(), event);
    }

    /**
     * Раздаёт комнате снимок идущего боя.
     *
     * Идёт по той же связи, что и лента: живое соединение у комнаты уже есть,
     * и заводить второе ради карусели незачем.
     *
     * @param nexusId Комната.
     * @param state Снимок боя.
     */
    void broadcastFightState(UUID nexusId, Object state) {
        send(new ChatRoom(nexusId), "fight-state", null, state);
    }

    private void send(ChatRoom room, String name, String id, Object payload) {
        subscribers.getOrDefault(room, new CopyOnWriteArrayList<>()).forEach(emitter -> {
            try {
                SseEmitter.SseEventBuilder frame = SseEmitter.event().name(name).data(payload);

                if (id != null) {
                    frame = frame.id(id);
                }

                emitter.send(frame);
            } catch (IOException exception) {
                remove(room, emitter);
                emitter.completeWithError(exception);
            }
        });
    }

    private void remove(ChatRoom room, SseEmitter emitter) {
        subscribers.computeIfPresent(room, (ignored, roomSubscribers) -> {
            roomSubscribers.remove(emitter);
            return roomSubscribers.isEmpty() ? null : roomSubscribers;
        });
    }
}
