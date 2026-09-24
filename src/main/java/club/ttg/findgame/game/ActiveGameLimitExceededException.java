package club.ttg.findgame.game;

public class ActiveGameLimitExceededException extends RuntimeException {

    private ActiveGameLimitExceededException(String message) {
        super(message);
    }

    public static ActiveGameLimitExceededException forFreeMaster() {
        return new ActiveGameLimitExceededException(
                "Без активной подписки можно иметь только одну незавершённую игру");
    }

    public static ActiveGameLimitExceededException forSubscriber(int limit) {
        return new ActiveGameLimitExceededException(
                "С подпиской можно иметь не больше %d незавершённых игр".formatted(limit));
    }
}
