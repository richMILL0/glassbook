package dev.glassbook.server.security;

import dev.glassbook.server.config.GlassbookProperties;
import dev.glassbook.server.user.UserEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final GlassbookProperties properties;

    public JwtService(JwtEncoder encoder, GlassbookProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public record Token(String value, Instant expiresAt) { }

    public Token issue(UserEntity user) {
        Instant now = Instant.now();
        Instant expires = now.plus(properties.security().tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("glassbook")
                .issuedAt(now)
                .expiresAt(expires)
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .claim("roles", List.of(user.getRole().name()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new Token(value, expires);
    }
}
