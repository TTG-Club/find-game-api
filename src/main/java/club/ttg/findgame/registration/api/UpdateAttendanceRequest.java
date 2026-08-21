package club.ttg.findgame.registration.api;

import club.ttg.findgame.registration.SessionAttendanceStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateAttendanceRequest(
        @NotNull SessionAttendanceStatus attendanceStatus
) {
}
