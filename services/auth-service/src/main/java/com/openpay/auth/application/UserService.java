package com.openpay.auth.application;

import com.openpay.auth.api.CreateUserRequest;
import com.openpay.auth.api.LoginRequest;
import com.openpay.auth.api.LoginResponse;
import com.openpay.auth.api.UserResponse;
import com.openpay.auth.domain.RefreshToken;
import com.openpay.auth.domain.RefreshTokenRepository;
import com.openpay.auth.domain.User;
import com.openpay.auth.domain.UserRepository;
import com.openpay.auth.infrastructure.MerchantServiceClient;
import com.openpay.audit.AuditAction;
import com.openpay.audit.AuditRecorder;
import com.openpay.email.EmailNotifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /**
     * A valid BCrypt hash of a value nobody knows, used to burn the same CPU time when the email
     * does not exist. Without it, "no such user" returns measurably faster than "wrong password",
     * and the login endpoint becomes a way to enumerate accounts.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /** 32 bytes of entropy, the same size an API key's secret half uses. */
    private static final int REFRESH_TOKEN_RANDOM_BYTES = 32;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SessionRevoker sessionRevoker;
    private final MerchantServiceClient merchantServiceClient;
    private final JwtIssuer jwtIssuer;
    private final ValidationAttemptLimiter attemptLimiter;
    private final AuditRecorder auditRecorder;
    private final EmailNotifier emailNotifier;
    private final int maxFailedLogins;
    private final int maxFailedLoginsPerSource;
    private final Duration failedLoginWindow;
    private final Duration refreshTokenTtl;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    public UserService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            SessionRevoker sessionRevoker,
            MerchantServiceClient merchantServiceClient,
            JwtIssuer jwtIssuer,
            ValidationAttemptLimiter attemptLimiter,
            AuditRecorder auditRecorder,
            EmailNotifier emailNotifier,
            @Value("${openpay.auth.max-failed-logins:10}") int maxFailedLogins,
            @Value("${openpay.auth.max-failed-logins-per-source:50}") int maxFailedLoginsPerSource,
            @Value("${openpay.auth.failed-login-window:PT15M}") Duration failedLoginWindow,
            @Value("${openpay.jwt.refresh-ttl:P30D}") Duration refreshTokenTtl) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.sessionRevoker = sessionRevoker;
        this.merchantServiceClient = merchantServiceClient;
        this.jwtIssuer = jwtIssuer;
        this.attemptLimiter = attemptLimiter;
        this.auditRecorder = auditRecorder;
        this.emailNotifier = emailNotifier;
        this.maxFailedLogins = maxFailedLogins;
        this.maxFailedLoginsPerSource = maxFailedLoginsPerSource;
        this.failedLoginWindow = failedLoginWindow;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (!merchantServiceClient.merchantExists(request.merchantId())) {
            throw new UnknownMerchantException(request.merchantId());
        }

        try {
            User user = userRepository.saveAndFlush(new User(
                    request.merchantId(),
                    request.email(),
                    passwordEncoder.encode(request.password()),
                    request.role()));
            log.info("Created user {} for merchant {}", user.getId(), user.getMerchantId());
            auditRecorder.record(AuditAction.USER_CREATED, "admin-token", user.getEmail(),
                    user.getMerchantId(), "Role " + user.getRole());
            return toResponse(user);
        } catch (DataIntegrityViolationException exception) {
            throw new InvalidApiKeyRequestException("A user with that email already exists");
        }
    }

    @Transactional
    public LoginResponse login(LoginRequest request, String sourceIp) {
        String email = request.email().trim().toLowerCase();
        String emailBucket = "login:" + email;
        String sourceBucket = "login-src:" + sourceIp;

        // Two budgets, because one alone is wrong in a different direction each way. Counting only
        // by email means anyone who knows an address can lock that person out by failing on
        // purpose. Counting only by source means an attacker spreading guesses across many
        // accounts from one host never trips anything. The source budget is much looser, since a
        // shared office IP or a NAT gateway legitimately produces many failures from many people.
        try {
            attemptLimiter.checkAllowed(emailBucket, maxFailedLogins);
            attemptLimiter.checkAllowed(sourceBucket, maxFailedLoginsPerSource);
        } catch (TooManyAttemptsException exception) {
            // Recorded separately from an ordinary failure: a throttled attempt never reached the
            // password check, and reading them as the same thing would misstate how far an attacker
            // actually got.
            auditRecorder.recordFailure(AuditAction.LOGIN_THROTTLED, email, null, null,
                    "Refused before the password was checked");
            throw exception;
        }

        Optional<User> found = userRepository.findByEmail(email);

        // Always verify against something, so a missing account costs the same as a wrong password.
        String hash = found.map(User::getPasswordHash).orElse(DUMMY_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);

        if (found.isEmpty() || !passwordMatches || !found.get().isActive()) {
            attemptLimiter.recordFailure(emailBucket, failedLoginWindow);
            attemptLimiter.recordFailure(sourceBucket, failedLoginWindow);
            log.warn("Failed login attempt for {}", email);
            // The email that was tried, even when no such account exists. A burst against one
            // address is the signal, and it is invisible if only real accounts are recorded.
            auditRecorder.recordFailure(AuditAction.LOGIN_FAILED, email, null,
                    found.map(User::getMerchantId).orElse(null),
                    found.isEmpty() ? "No such account" : "Wrong password or inactive account");
            throw new InvalidCredentialsException();
        }

        User user = found.get();
        // Only the account's own budget is cleared. The source budget deliberately survives a
        // success, or an attacker with one valid account of their own would reset it at will and
        // guess against everyone else for free.
        attemptLimiter.recordSuccess(emailBucket);
        user.recordLogin();

        JwtIssuer.IssuedToken issued =
                jwtIssuer.issue(user.getId(), user.getMerchantId(), user.getEmail(), user.getRole());
        IssuedRefreshToken refresh = issueRefreshToken(user.getId());

        log.info("User {} logged in for merchant {}", user.getId(), user.getMerchantId());
        auditRecorder.record(AuditAction.LOGIN_SUCCEEDED, user.getEmail(), user.getId().toString(),
                user.getMerchantId(), "Role " + user.getRole());
        return new LoginResponse(
                issued.token(),
                issued.expiresAt().atOffset(ZoneOffset.UTC),
                refresh.rawToken(),
                refresh.entity().getExpiresAt(),
                user.getId(),
                user.getMerchantId(),
                user.getEmail(),
                user.getRole());
    }

    /**
     * Renews a session without asking for a password again.
     *
     * <p>Rotation, not reuse: the presented token is revoked here and a new one takes its place,
     * every single time. A refresh token that could be used repeatedly would be a password that
     * never has to be typed again — stealing it once would be enough, forever. Rotation bounds the
     * damage to whichever single token was actually stolen.
     *
     * <p>Reuse of an already-rotated token is treated as theft, not as a race: two legitimate
     * clients racing to refresh the same token is not a scenario this dashboard's traffic pattern
     * produces, and a stolen token being replayed after its rightful owner already rotated past it
     * looks identical to that race. Erring toward "assume theft" and revoking every session is the
     * safer read of an ambiguous signal on something this sensitive.
     */
    @Transactional
    public LoginResponse refresh(String rawToken) {
        String hash = hash(rawToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(InvalidRefreshTokenException::new);

        if (stored.wasRotatedAway()) {
            // Delegated to SessionRevoker, a separate bean, rather than done here: this method is
            // about to throw, @Transactional rolls back on any RuntimeException including the one
            // about to be thrown, and a revocation sharing this transaction would be undone by
            // that very rollback. See SessionRevoker's own javadoc for why the fix has to be a
            // separate bean and not just a REQUIRES_NEW method on this class.
            sessionRevoker.revokeAllSessions(stored.getUserId());
            log.warn("Refresh token reuse detected for user {}; every session revoked", stored.getUserId());
            auditRecorder.recordFailure(AuditAction.REFRESH_TOKEN_REUSE_DETECTED,
                    stored.getUserId().toString(), null, null,
                    "A rotated-away refresh token was presented again; all sessions revoked");
            // Told, not just logged: the account holder is the one person who can say whether this
            // was really them. A lookup here, not a field carried on RefreshToken, because the
            // email can change after the token was issued and the alert should go to the current
            // address.
            userRepository.findById(stored.getUserId()).ifPresent(user -> emailNotifier.sendBestEffort(
                    user.getEmail(),
                    "Security alert: you were signed out everywhere",
                    "We detected a sign-in token being used a second time after it had already been "
                            + "renewed once — a sign it may have been copied. As a precaution, every "
                            + "session on your account was ended and you will need to sign in again "
                            + "wherever you are using OpenPay. If this was not expected, we recommend "
                            + "changing your password."));
            throw new InvalidRefreshTokenException();
        }
        if (!stored.isUsable()) {
            // Expired, or explicitly logged out. Neither is theft — no need to escalate.
            throw new InvalidRefreshTokenException();
        }

        User user = userRepository.findById(stored.getUserId())
                .filter(User::isActive)
                .orElseThrow(InvalidRefreshTokenException::new);

        JwtIssuer.IssuedToken issued =
                jwtIssuer.issue(user.getId(), user.getMerchantId(), user.getEmail(), user.getRole());
        IssuedRefreshToken newRefresh = issueRefreshToken(user.getId());
        stored.rotateTo(newRefresh.entity().getId());

        log.info("Refreshed session for user {}", user.getId());
        auditRecorder.record(AuditAction.SESSION_REFRESHED, user.getEmail(), user.getId().toString(),
                user.getMerchantId(), "Role " + user.getRole());
        return new LoginResponse(
                issued.token(),
                issued.expiresAt().atOffset(ZoneOffset.UTC),
                newRefresh.rawToken(),
                newRefresh.entity().getExpiresAt(),
                user.getId(),
                user.getMerchantId(),
                user.getEmail(),
                user.getRole());
    }

    /**
     * Ends one session. The access token already issued keeps working until its own short expiry —
     * the same trade {@link JwtIssuer} already documents for a disabled user — but nothing can mint
     * a fresh one from this refresh token again.
     */
    @Transactional
    public void logout(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken)).ifPresent(stored -> {
            stored.revoke();
            log.info("User {} logged out", stored.getUserId());
            auditRecorder.record(AuditAction.LOGOUT, stored.getUserId().toString(), null, null, null);
        });
        // Presenting a token that does not exist is not an error: logout is idempotent, and the
        // caller's goal — this token no longer works — is already true either way.
    }

    private IssuedRefreshToken issueRefreshToken(java.util.UUID userId) {
        String rawToken = randomHex(REFRESH_TOKEN_RANDOM_BYTES);
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(refreshTokenTtl);
        RefreshToken entity = refreshTokenRepository.saveAndFlush(
                new RefreshToken(userId, hash(rawToken), expiresAt));
        return new IssuedRefreshToken(rawToken, entity);
    }

    private String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String hash(String rawValue) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawValue.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 should always be available", exception);
        }
    }

    /** The plaintext, returned to the caller exactly once, alongside the row that stores its hash. */
    private record IssuedRefreshToken(String rawToken, RefreshToken entity) {
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getMerchantId(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getLastLoginAt());
    }

    /** Kept for symmetry with the response type; unused fields are not exposed. */
    @Transactional(readOnly = true)
    public Optional<OffsetDateTime> lastLogin(String email) {
        return userRepository.findByEmail(email.trim().toLowerCase()).map(User::getLastLoginAt);
    }
}
