package club.ttg.findgame.follow;

/** Отметка или приглашение не по адресу: себя, чужой игрой, неотмеченного. */
public class FollowNotAllowedException extends RuntimeException {

    public FollowNotAllowedException(String message) {
        super(message);
    }
}
