package club.ttg.findgame.registration.api;

import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record CreateSessionRegistrationRequest(
        @URL @Size(max = 2048) String characterSheetUrl
) {
}
