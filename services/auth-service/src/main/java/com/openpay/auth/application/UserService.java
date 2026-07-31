package com.openpay.auth.application;

import com.openpay.auth.api.CreateUserRequest;
import com.openpay.auth.api.LoginRequest;
import com.openpay.auth.api.LoginResponse;
import com.openpay.auth.api.UserResponse;
import com.openpay.auth.domain.User;
import com.openpay.auth.domain.UserRepository;
import com.openpay.auth.infrastructure.MerchantServiceClient;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final UserRepository userRepository;
    private final MerchantServiceClient merchantServiceClient;
    private final JwtIssuer jwtIssuer;
    private final ValidationAttemptLimiter attemptLimiter;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(
            UserRepository userRepository,
            MerchantServiceClient merchantServiceClient,
            JwtIssuer jwtIssuer,
            ValidationAttemptLimiter attemptLimiter) {
        this.userRepository = userRepository;
        this.merchantServiceClient = merchantServiceClient;
        this.jwtIssuer = jwtIssuer;
        this.attemptLimiter = attemptLimiter;
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
            return toResponse(user);
        } catch (DataIntegrityViolationException exception) {
            throw new InvalidApiKeyRequestException("A user with that email already exists");
        }
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        attemptLimiter.checkAllowed("login:" + email);

        Optional<User> found = userRepository.findByEmail(email);

        // Always verify against something, so a missing account costs the same as a wrong password.
        String hash = found.map(User::getPasswordHash).orElse(DUMMY_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);

        if (found.isEmpty() || !passwordMatches || !found.get().isActive()) {
            attemptLimiter.recordFailure("login:" + email);
            log.warn("Failed login attempt for {}", email);
            throw new InvalidCredentialsException();
        }

        User user = found.get();
        attemptLimiter.recordSuccess("login:" + email);
        user.recordLogin();

        JwtIssuer.IssuedToken issued =
                jwtIssuer.issue(user.getId(), user.getMerchantId(), user.getEmail(), user.getRole());

        log.info("User {} logged in for merchant {}", user.getId(), user.getMerchantId());
        return new LoginResponse(
                issued.token(),
                issued.expiresAt().atOffset(ZoneOffset.UTC),
                user.getId(),
                user.getMerchantId(),
                user.getEmail(),
                user.getRole());
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
