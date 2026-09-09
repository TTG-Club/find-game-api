package club.ttg.findgame.registration.api;

import club.ttg.findgame.registration.SessionAttendanceStatus;
import java.time.Instant;
import java.util.UUID;

/** Присутствие на ближайшей встрече без сведений об оплате. */
public record GameParticipantSessionResponse(
        UUID id, Instant startsAt, Integer estimatedDurationMinutes,
        SessionAttendanceStatus attendanceStatus
) {}
