package club.ttg.findgame.chat;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class ChatLiveEventListener {

    private final ChatEventBroadcaster broadcaster;

    ChatLiveEventListener(ChatEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onSaved(ChatEventSaved saved) {
        broadcaster.broadcast(saved.event());
    }
}
