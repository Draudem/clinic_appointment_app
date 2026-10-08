package com.clinic.assistant.security;

import com.clinic.assistant.user.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Извлекает JWT из заголовка {@code Authorization: Bearer ...}
 * и кладёт аутентификацию в SecurityContext.
 *
 * <p>Невалидный или отсутствующий токен — это не ошибка фильтра:
 * запрос продолжается анонимно, а защищённые эндпоинты сами ответят 401.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                Long uid = jwtService.userId(claims);
                var user = uid != null
                        ? userRepository.findById(uid).orElse(null)
                        : userRepository.findByLogin(claims.getSubject()).orElse(null);
                if (user != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    var principal = new AuthPrincipal(user.getId(), user.getLogin(), user.getRole());
                    var authentication = new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception ignored) {
                // просроченный/испорченный токен → остаёмся анонимными
            }
        }
        chain.doFilter(request, response);
    }
}
