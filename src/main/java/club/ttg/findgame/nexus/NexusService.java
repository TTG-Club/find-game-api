package club.ttg.findgame.nexus;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.nexus.api.CreateNexusRequest;
import club.ttg.findgame.nexus.api.NexusMemberResponse;
import club.ttg.findgame.nexus.api.NexusResponse;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.RegistrationStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Игровые комнаты.
 *
 * Комната бывает двух происхождений, и различает их состав. В самостоятельную
 * входят по ссылке: перешедший становится участником сам, и дальше она видна
 * ему в списке. Комната игры состава не хранит — в неё пускают тех, кто подал
 * заявку, и мастера; ссылки у неё нет, потому что попадают туда со страницы
 * игры.
 */
@Service
public class NexusService {

    private final NexusRepository nexusRepository;
    private final NexusMemberRepository memberRepository;
    private final GameRepository gameRepository;
    private final GameRegistrationRepository registrationRepository;

    public NexusService(
            NexusRepository nexusRepository,
            NexusMemberRepository memberRepository,
            GameRepository gameRepository,
            GameRegistrationRepository registrationRepository
    ) {
        this.nexusRepository = nexusRepository;
        this.memberRepository = memberRepository;
        this.gameRepository = gameRepository;
        this.registrationRepository = registrationRepository;
    }

    /**
     * Заводит самостоятельную комнату.
     *
     * @param ownerId Владелец из токена.
     * @param request Название комнаты.
     * @return Созданная комната вместе с кодом приглашения.
     */
    @Transactional
    public NexusResponse create(UUID ownerId, CreateNexusRequest request) {
        Nexus nexus = new Nexus();

        nexus.setTitle(request.title().strip());
        nexus.setOwnerId(ownerId);
        nexus.setInviteCode(UUID.randomUUID());

        return toResponse(nexusRepository.save(nexus), ownerId);
    }

    /**
     * Комнаты пользователя: свои и те, куда он вошёл по ссылке.
     *
     * @param userId Пользователь из токена.
     * @param page Страница выдачи.
     * @param size Размер страницы.
     * @return Комнаты, свежие первыми.
     */
    @Transactional(readOnly = true)
    public Page<NexusResponse> findAvailable(UUID userId, int page, int size) {
        PageRequest pageable = PageRequest.of(
                page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        return nexusRepository.findAllAvailable(userId, pageable)
                .map(nexus -> toResponse(nexus, userId));
    }

    /**
     * Открывает комнату.
     *
     * @param userId Пользователь из токена.
     * @param nexusId Комната.
     * @return Комната глазами открывшего.
     */
    @Transactional(readOnly = true)
    public NexusResponse get(UUID userId, UUID nexusId) {
        Nexus nexus = nexusRepository.findById(nexusId)
                .orElseThrow(() -> new NexusNotFoundException(nexusId));

        requireAccess(nexus, userId);

        return toResponse(nexus, userId);
    }

    /**
     * Впускает по ссылке-приглашению.
     *
     * Отдельного согласия не спрашивают: переход по ссылке и есть согласие, а
     * лишний шаг «точно войти?» только мешал бы позвавшему собрать группу.
     *
     * @param userId Пользователь из токена.
     * @param inviteCode Код из ссылки.
     * @return Комната, в которую вошли.
     */
    @Transactional
    public NexusResponse joinByInvite(UUID userId, UUID inviteCode) {
        Nexus nexus = nexusRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new NexusNotFoundException(
                        "Приглашение недействительно"));

        if (!nexus.getOwnerId().equals(userId)
                && !memberRepository.existsByNexusIdAndUserId(nexus.getId(), userId)) {
            try {
                memberRepository.saveAndFlush(NexusMember.of(nexus.getId(), userId));
            } catch (DataIntegrityViolationException exception) {
                // Двойной переход по одной ссылке — не ошибка: человек уже внутри.
            }
        }

        return toResponse(nexus, userId);
    }

    /**
     * Комната игры. Заводится при первом входе, а не вместе с игрой: у
     * большинства игр она так и не понадобится, а пустые комнаты пришлось бы
     * держать и чистить.
     *
     * @param userId Пользователь из токена.
     * @param gameId Игра.
     * @return Комната игры.
     */
    @Transactional
    public NexusResponse getForGame(UUID userId, UUID gameId) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        requireGameAccess(game, userId);

        Nexus nexus = nexusRepository.findByGameId(gameId)
                .orElseGet(() -> createForGame(game));

        return toResponse(nexus, userId);
    }

    /**
     * Состав комнаты.
     *
     * @param userId Пользователь из токена.
     * @param nexusId Комната.
     * @return Участники в порядке появления; владелец первым.
     */
    @Transactional(readOnly = true)
    public List<NexusMemberResponse> findMembers(UUID userId, UUID nexusId) {
        Nexus nexus = nexusRepository.findById(nexusId)
                .orElseThrow(() -> new NexusNotFoundException(nexusId));

        requireAccess(nexus, userId);

        List<NexusMemberResponse> members = new ArrayList<>();

        members.add(new NexusMemberResponse(
                nexus.getOwnerId(), true, nexus.getCreatedAt()));

        if (nexus.isGameRoom()) {
            registrationRepository
                    .findAllByGameIdOrderByCreatedAtAsc(nexus.getGameId()).stream()
                    .filter(registration ->
                            registration.getStatus() != RegistrationStatus.REJECTED)
                    .forEach(registration -> members.add(new NexusMemberResponse(
                            registration.getPlayerId(), false, registration.getCreatedAt())));

            return members;
        }

        memberRepository.findAllByNexusIdOrderByJoinedAtAsc(nexusId)
                .forEach(member -> members.add(new NexusMemberResponse(
                        member.getUserId(), false, member.getJoinedAt())));

        return members;
    }

    /**
     * Выводит участника из самостоятельной комнаты: сам себя или кого угодно,
     * если это владелец.
     *
     * Состав комнаты игры так не меняют — он идёт от заявок, и уйти из него
     * значит отозвать заявку.
     *
     * @param userId Пользователь из токена.
     * @param nexusId Комната.
     * @param memberId Кого выводят.
     */
    @Transactional
    public void removeMember(UUID userId, UUID nexusId, UUID memberId) {
        Nexus nexus = nexusRepository.findById(nexusId)
                .orElseThrow(() -> new NexusNotFoundException(nexusId));

        if (nexus.isGameRoom()) {
            throw new InvalidNexusException(
                    "Состав комнаты игры определяют заявки в игру");
        }
        if (nexus.getOwnerId().equals(memberId)) {
            throw new InvalidNexusException("Владелец не покидает свою комнату");
        }
        if (!nexus.getOwnerId().equals(userId) && !userId.equals(memberId)) {
            throw new NexusAccessDeniedException("Это чужая комната");
        }

        memberRepository.deleteByNexusIdAndUserId(nexusId, memberId);
    }

    /**
     * Пускает ли комната этого пользователя. Нужна соседним разделам —
     * чату и листам, — чтобы не повторять правила доступа у каждого.
     *
     * @param nexusId Комната.
     * @param userId Пользователь.
     */
    @Transactional(readOnly = true)
    public boolean hasAccess(UUID nexusId, UUID userId) {
        return nexusRepository.findById(nexusId)
                .map(nexus -> allows(nexus, userId))
                .orElse(false);
    }

    /**
     * Комната игры, если она уже заведена.
     *
     * Нужна соседним разделам: событие игры пишется в её чат, а до первого
     * входа комнаты может и не быть.
     *
     * @param gameId Игра.
     * @return Комната игры или пусто.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findGameNexusId(UUID gameId) {
        return nexusRepository.findByGameId(gameId).map(Nexus::getId);
    }

    private Nexus createForGame(Game game) {
        Nexus nexus = new Nexus();

        nexus.setTitle(game.getTitle());
        nexus.setOwnerId(game.getMasterId());
        nexus.setGameId(game.getId());

        try {
            return nexusRepository.saveAndFlush(nexus);
        } catch (DataIntegrityViolationException exception) {
            // Двое вошли одновременно — комната у игры одна, берём готовую.
            return nexusRepository.findByGameId(game.getId())
                    .orElseThrow(() -> new NexusNotFoundException(
                            "Комната игры не создалась"));
        }
    }

    private void requireAccess(Nexus nexus, UUID userId) {
        if (!allows(nexus, userId)) {
            // Чужая комната не должна отличаться от несуществующей: иначе по
            // ответу можно перебирать чужие идентификаторы.
            throw new NexusNotFoundException(nexus.getId());
        }
    }

    private boolean allows(Nexus nexus, UUID userId) {
        if (nexus.getOwnerId().equals(userId)) {
            return true;
        }
        if (nexus.isGameRoom()) {
            return registrationRepository.existsByGameIdAndPlayerIdAndStatusNot(
                    nexus.getGameId(), userId, RegistrationStatus.REJECTED);
        }

        return memberRepository.existsByNexusIdAndUserId(nexus.getId(), userId);
    }

    /** В комнату игры пускает заявка: мастера — его игра, игрока — поданная. */
    private void requireGameAccess(Game game, UUID userId) {
        if (game.getMasterId().equals(userId)) {
            return;
        }
        if (!registrationRepository.existsByGameIdAndPlayerIdAndStatusNot(
                game.getId(), userId, RegistrationStatus.REJECTED)) {
            throw new NexusAccessDeniedException(
                    "Комната игры открыта тем, кто подал заявку");
        }
    }

    private int countMembers(Nexus nexus) {
        if (nexus.isGameRoom()) {
            return (int) registrationRepository
                    .findAllByGameIdOrderByCreatedAtAsc(nexus.getGameId()).stream()
                    .filter(registration ->
                            registration.getStatus() != RegistrationStatus.REJECTED)
                    .count() + 1;
        }

        return memberRepository.findAllByNexusIdOrderByJoinedAtAsc(nexus.getId()).size() + 1;
    }

    private NexusResponse toResponse(Nexus nexus, UUID userId) {
        boolean owner = nexus.getOwnerId().equals(userId);

        return new NexusResponse(
                nexus.getId(),
                nexus.getTitle(),
                nexus.getOwnerId(),
                // Код приглашения — право звать в комнату, и оно есть только
                // у владельца.
                owner ? nexus.getInviteCode() : null,
                nexus.getGameId(),
                owner,
                countMembers(nexus),
                nexus.getCreatedAt(),
                nexus.getUpdatedAt());
    }
}
