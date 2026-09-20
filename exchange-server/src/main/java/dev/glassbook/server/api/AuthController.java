package dev.glassbook.server.api;

import dev.glassbook.server.security.JwtService;
import dev.glassbook.server.user.Role;
import dev.glassbook.server.user.UserEntity;
import dev.glassbook.server.user.UserRepository;
import dev.glassbook.server.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{3,32}$", message = "Должен содержать 3-32 символа, числа, . _ -")
            String username,
            @NotBlank @Size(min = 8, max = 72) String password) { }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) { }

    public record AuthResponse(String token, Instant expiresAt, long participantId, String username, String role,
                               boolean welcomeFundsGranted) { }

    private final UserService userService;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final String dummyHash;

    public AuthController(UserService userService, UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.userService = userService;
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    @PostMapping("/register")
    public CompletableFuture<ResponseEntity<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        UserEntity user = userService.create(request.username(), request.password(), Role.TRADER);
        return userService.grantWelcomeFunds(user.getId())
                .handle((ok, error) -> {
                    if (error != null) {
                        log.warn("welcome funds for user {} failed: {}", user.getId(), error.toString());
                    }
                    return response(user, error == null, HttpStatus.CREATED);
                });
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        UserEntity user = users.findByUsername(request.username()).orElse(null);
        boolean matches = encoder.matches(request.password(), user != null ? user.getPasswordHash() : dummyHash);
        if (user == null || !matches || user.getRole() == Role.BOT) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Неверный username или пароль");
        }
        return response(user, true, HttpStatus.OK);
    }

    private ResponseEntity<AuthResponse> response(UserEntity user, boolean funded, HttpStatus status) {
        JwtService.Token token = jwt.issue(user);
        return ResponseEntity.status(status).body(new AuthResponse(token.value(), token.expiresAt(), user.getId(),
                user.getUsername(), user.getRole().name(), funded));
    }
}
