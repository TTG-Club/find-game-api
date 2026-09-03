package club.ttg.findgame.chat;

import club.ttg.findgame.nexus.NexusFightStateSaved;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Раздаёт комнате снимок идущего боя.
 *
 * Живая связь у комнаты одна — та, по которой идёт лента; карусель едет по
 * ней же, отдельным видом кадра.
 */
@Component
class NexusFightLiveListener {

    private final ChatEventBroadcaster broadcaster;

    NexusFightLiveListener(ChatEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onSaved(NexusFightStateSaved saved) {
        broadcaster.broadcastFightState(saved.nexusId(), saved.state());
    }
}
