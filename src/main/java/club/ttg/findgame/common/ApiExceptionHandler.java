package club.ttg.findgame.common;

import club.ttg.findgame.chat.ChatAccessDeniedException;
import club.ttg.findgame.nexus.InvalidNexusException;
import club.ttg.findgame.nexus.NexusAccessDeniedException;
import club.ttg.findgame.nexus.NexusNotFoundException;
import club.ttg.findgame.chat.InvalidChatEventException;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameAccessDeniedException;
import club.ttg.findgame.game.ActiveGameLimitExceededException;
import club.ttg.findgame.game.InvalidGameDetailsException;
import club.ttg.findgame.game.InvalidPlayerCountException;
import club.ttg.findgame.game.GameCannotBeRaisedException;
import club.ttg.findgame.game.GameRaiseCooldownException;
import club.ttg.findgame.session.GameSessionAccessDeniedException;
import club.ttg.findgame.session.InvalidGameSessionCostException;
import club.ttg.findgame.session.InvalidGameSessionDateException;
import club.ttg.findgame.session.InvalidGameSessionStateException;
import club.ttg.findgame.notification.NotificationNotFoundException;
import club.ttg.findgame.session.GameSessionNotFoundException;
import club.ttg.findgame.registration.InvalidSessionRegistrationException;
import club.ttg.findgame.registration.SessionRegistrationAccessDeniedException;
import club.ttg.findgame.registration.SessionRegistrationNotFoundException;
import club.ttg.findgame.profile.InvalidUserProfileException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(GameNotFoundException.class)
    ProblemDetail handleNotFound(GameNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Игра не найдена", exception.getMessage());
    }

    @ExceptionHandler(InvalidPlayerCountException.class)
    ProblemDetail handleInvalidPlayers(InvalidPlayerCountException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Некорректное количество игроков", exception.getMessage());
    }

    @ExceptionHandler(InvalidGameDetailsException.class)
    ProblemDetail handleInvalidDetails(InvalidGameDetailsException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Некорректные параметры игры", exception.getMessage());
    }

    @ExceptionHandler(ActiveGameLimitExceededException.class)
    ProblemDetail handleActiveGameLimit(ActiveGameLimitExceededException exception) {
        return problem(HttpStatus.CONFLICT, "Достигнут лимит активных игр", exception.getMessage());
    }

    @ExceptionHandler(GameRaiseCooldownException.class)
    ProblemDetail handleGameRaiseCooldown(GameRaiseCooldownException exception) {
        ProblemDetail detail = problem(
                HttpStatus.TOO_MANY_REQUESTS,
                "Игру пока нельзя поднять",
                exception.getMessage());
        detail.setProperty("availableAt", exception.getAvailableAt());
        return detail;
    }

    @ExceptionHandler(GameCannotBeRaisedException.class)
    ProblemDetail handleGameCannotBeRaised(GameCannotBeRaisedException exception) {
        return problem(HttpStatus.CONFLICT, "Игру нельзя поднять", exception.getMessage());
    }

    @ExceptionHandler(GameAccessDeniedException.class)
    ProblemDetail handleGameAccessDenied(GameAccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Доступ запрещён", exception.getMessage());
    }

    @ExceptionHandler(GameSessionAccessDeniedException.class)
    ProblemDetail handleSessionAccessDenied(GameSessionAccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Доступ запрещён", exception.getMessage());
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    ProblemDetail handleNotificationNotFound(NotificationNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Уведомление не найдено", exception.getMessage());
    }

    @ExceptionHandler(GameSessionNotFoundException.class)
    ProblemDetail handleSessionNotFound(GameSessionNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Сессия не найдена", exception.getMessage());
    }

    @ExceptionHandler(InvalidGameSessionCostException.class)
    ProblemDetail handleInvalidSessionCost(InvalidGameSessionCostException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Некорректная стоимость сессии", exception.getMessage());
    }

    @ExceptionHandler(InvalidGameSessionDateException.class)
    ProblemDetail handleInvalidSessionDate(InvalidGameSessionDateException exception) {
        return problem(HttpStatus.CONFLICT, "Дату сессии нельзя изменить", exception.getMessage());
    }

    @ExceptionHandler(InvalidGameSessionStateException.class)
    ProblemDetail handleInvalidSessionState(InvalidGameSessionStateException exception) {
        return problem(HttpStatus.CONFLICT, "Состояние сессии нельзя изменить", exception.getMessage());
    }

    @ExceptionHandler(SessionRegistrationNotFoundException.class)
    ProblemDetail handleRegistrationNotFound(SessionRegistrationNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Заявка не найдена", exception.getMessage());
    }

    @ExceptionHandler(SessionRegistrationAccessDeniedException.class)
    ProblemDetail handleRegistrationAccessDenied(SessionRegistrationAccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Доступ запрещён", exception.getMessage());
    }

    @ExceptionHandler(InvalidSessionRegistrationException.class)
    ProblemDetail handleInvalidRegistration(InvalidSessionRegistrationException exception) {
        return problem(HttpStatus.CONFLICT, "Заявка не может быть обработана", exception.getMessage());
    }

    @ExceptionHandler(InvalidUserProfileException.class)
    ProblemDetail handleInvalidUserProfile(InvalidUserProfileException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Некорректный профиль", exception.getMessage());
    }

    @ExceptionHandler(ChatAccessDeniedException.class)
    ProblemDetail handleChatAccessDenied(ChatAccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Доступ к чату запрещён", exception.getMessage());
    }

    @ExceptionHandler(InvalidChatEventException.class)
    ProblemDetail handleInvalidChatEvent(InvalidChatEventException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Некорректное событие чата", exception.getMessage());
    }

    @ExceptionHandler(NexusNotFoundException.class)
    ProblemDetail handleNexusNotFound(NexusNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Нексус не найден", exception.getMessage());
    }

    @ExceptionHandler(NexusAccessDeniedException.class)
    ProblemDetail handleNexusAccessDenied(NexusAccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Доступ к нексусу запрещён", exception.getMessage());
    }

    @ExceptionHandler(InvalidNexusException.class)
    ProblemDetail handleInvalidNexus(InvalidNexusException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Некорректное действие с нексусом", exception.getMessage());
    }

    /**
     * Нарушение ограничения хранилища.
     *
     * Раньше такое уходило голым 500 мимо этого обработчика и мимо логов:
     * клиент видел «Internal Server Error», а причина — имя нарушённого
     * ограничения — не попадала никуда. Теперь она и пишется в лог, и уходит
     * в ответ: без неё чинить нечего.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException exception) {
        log.error("Нарушено ограничение хранилища", exception);

        return problem(HttpStatus.CONFLICT, "Запись отклонена хранилищем",
                constraintOf(exception));
    }

    /** Имя нарушенного ограничения; сырой SQL наружу не уходит. */
    private static String constraintOf(DataIntegrityViolationException exception) {
        Throwable cause = exception.getCause();

        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && violation.getConstraintName() != null) {
                return "Нарушено ограничение " + violation.getConstraintName();
            }

            cause = cause.getCause();
        }

        return "Данные не прошли проверку хранилища";
    }

    @ExceptionHandler(DataAccessException.class)
    ProblemDetail handleDataAccess(DataAccessException exception) {
        log.error("Хранилище отклонило запрос", exception);

        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Ошибка хранилища",
                "Запрос к хранилищу не выполнен");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "Ошибка валидации", "Проверьте поля запроса");
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        detail.setProperty("errors", errors);
        return detail;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Ошибка валидации", exception.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String title, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(title);
        detail.setType(URI.create("https://find-game.ttg.club/problems/" + status.value()));
        return detail;
    }
}
