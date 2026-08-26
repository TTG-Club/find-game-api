package club.ttg.findgame.common;

import club.ttg.findgame.chat.ChatAccessDeniedException;
import club.ttg.findgame.chat.InvalidChatEventException;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameAccessDeniedException;
import club.ttg.findgame.game.ActiveGameLimitExceededException;
import club.ttg.findgame.game.InvalidGameDetailsException;
import club.ttg.findgame.game.InvalidPlayerCountException;
import club.ttg.findgame.session.GameSessionAccessDeniedException;
import club.ttg.findgame.session.InvalidGameSessionCostException;
import club.ttg.findgame.session.GameSessionNotFoundException;
import club.ttg.findgame.registration.InvalidSessionRegistrationException;
import club.ttg.findgame.registration.SessionRegistrationAccessDeniedException;
import club.ttg.findgame.registration.SessionRegistrationNotFoundException;
import club.ttg.findgame.profile.InvalidUserProfileException;
import jakarta.validation.ConstraintViolationException;
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

    @ExceptionHandler(GameAccessDeniedException.class)
    ProblemDetail handleGameAccessDenied(GameAccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Доступ запрещён", exception.getMessage());
    }

    @ExceptionHandler(GameSessionAccessDeniedException.class)
    ProblemDetail handleSessionAccessDenied(GameSessionAccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Доступ запрещён", exception.getMessage());
    }

    @ExceptionHandler(GameSessionNotFoundException.class)
    ProblemDetail handleSessionNotFound(GameSessionNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Сессия не найдена", exception.getMessage());
    }

    @ExceptionHandler(InvalidGameSessionCostException.class)
    ProblemDetail handleInvalidSessionCost(InvalidGameSessionCostException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Некорректная стоимость сессии", exception.getMessage());
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
