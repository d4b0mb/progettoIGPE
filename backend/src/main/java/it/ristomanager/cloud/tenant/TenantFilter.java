package it.ristomanager.cloud.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * After the login token has been verified, copies its restaurant into
 * {@link TenantContext} for the duration of the request. Requests without a
 * verified token (sign-up, login) run with no restaurant, which means the
 * database shows them no tenant data.
 */
public class TenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        try {
            if (auth instanceof JwtAuthenticationToken token) {
                Jwt jwt = token.getToken();
                TenantContext.set(UUID.fromString(jwt.getClaimAsString("rid")));
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
