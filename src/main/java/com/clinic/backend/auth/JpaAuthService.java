package com.clinic.backend.auth;

import com.clinic.backend.auth.dto.AuthResponse;
import com.clinic.backend.auth.dto.LoginRequest;
import com.clinic.backend.auth.dto.RegisterRequest;
import com.clinic.backend.security.JwtService;
import com.clinic.backend.user.Role;
import com.clinic.backend.user.User;
import com.clinic.backend.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaAuthService implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public JpaAuthService(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String login = request.login().trim();
        if (userRepository.existsByLogin(login)) {
            throw new LoginAlreadyExistsException(login);
        }

        Role role = parseRole(request.role());
        User user = new User(login, passwordEncoder.encode(request.password()), role);
        userRepository.save(user);

        return AuthResponse.user(user.getLogin(), user.getRole().name());
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByLogin(request.login().trim())
                .orElseThrow(BadCredentialsException::new);
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw newBadCredentials();
        }
        String token = jwtService.issue(user.getLogin(), user.getRole());
        return new AuthResponse(token, user.getLogin(), user.getRole().name());
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse me(String login) {
        User user = userRepository.findByLogin(login)
                .orElseThrow(JpaAuthService::newBadCredentials);
        return AuthResponse.user(user.getLogin(), user.getRole().name());
    }

    private static BadCredentialsException newBadCredentials() {
        return new BadCredentialsException();
    }

    private static Role parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return Role.PATIENT;
        }
        try {
            return Role.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Неизвестная роль: " + raw);
        }
    }
}
