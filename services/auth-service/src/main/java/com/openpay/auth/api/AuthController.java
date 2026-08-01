package com.openpay.auth.api;

import com.openpay.auth.application.ApiKeyService;
import com.openpay.auth.application.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final ApiKeyService apiKeyService;
    private final UserService userService;

    public AuthController(ApiKeyService apiKeyService, UserService userService) {
        this.apiKeyService = apiKeyService;
        this.userService = userService;
    }

    @PostMapping("/api-keys")
    public ResponseEntity<CreateApiKeyResponse> createApiKey(@Valid @RequestBody CreateApiKeyRequest request) {
        CreateApiKeyResponse response = apiKeyService.createApiKey(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    /** Human login. Public by design: it is how a session begins. */
    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return userService.login(request, sourceIp(httpRequest));
    }

    /**
     * The caller's address, for the per-source half of the login throttle.
     *
     * <p>Reads {@code X-Forwarded-For} because in every real deployment this sits behind a proxy
     * and the socket address would otherwise be the proxy's, collapsing every user in the world
     * into one bucket. Only the first hop is taken — the rest of the header is client-supplied and
     * trivially forged.
     *
     * <p>The header itself is forgeable when nothing strips it at the edge, which is a real
     * weakness of this budget: an attacker can rotate the value and get a fresh source bucket each
     * time. It is a defence in depth on top of the per-account budget, not a substitute for it,
     * and the ingress is the right place to make the header trustworthy.
     */
    private String sourceIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Creating a dashboard user is a platform-operator action, like issuing an API key. */
    @PostMapping("/users")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponse response = userService.createUser(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("/auth/validate-key")
    public ValidateApiKeyResponse validateKey(@Valid @RequestBody ValidateApiKeyRequest request) {
        return apiKeyService.validateKey(request.apiKey());
    }
}
