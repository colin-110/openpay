package com.openpay.auth.api;

import com.openpay.auth.application.ApiKeyService;
import com.openpay.auth.application.UserService;
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
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return userService.login(request);
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
