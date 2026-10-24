package it.ristomanager.cloud.security;

import it.ristomanager.cloud.user.AppUser;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public static final String ISSUER = "ristomanager-cloud";

    public record IssuedToken(String value, long expiresInSeconds) {
    }

    private final JwtEncoder encoder;
    private final SecurityProperties props;

    JwtService(JwtEncoder encoder, SecurityProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public IssuedToken issue(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTtl()))
                .claim("rid", user.restaurantId().toString())
                .claim("role", user.role().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, props.accessTtl().toSeconds());
    }
}
