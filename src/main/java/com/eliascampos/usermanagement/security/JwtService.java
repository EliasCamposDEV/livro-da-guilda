package com.eliascampos.usermanagement.security;

import com.eliascampos.usermanagement.auth.InvalidRefreshTokenException;
import com.eliascampos.usermanagement.auth.dto.TokenResponse;
import com.eliascampos.usermanagement.user.User;
import com.eliascampos.usermanagement.user.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import com.mongodb.client.result.UpdateResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class JwtService {

    public static final String TOKEN_TYPE_CLAIM = "type";
    public static final String ACCESS_TOKEN_TYPE = "access";
    public static final String REFRESH_TOKEN_TYPE = "refresh";

    private static final String ROLES_CLAIM = "roles";
    private static final String TOKEN_VERSION_CLAIM = "ver";

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder refreshTokenDecoder;
    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;
    private final String issuer;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtService(JwtEncoder jwtEncoder,
                      @Qualifier("refreshTokenDecoder") JwtDecoder refreshTokenDecoder,
                      UserRepository userRepository,
                       MongoTemplate mongoTemplate,
                      @Value("${app.security.jwt.issuer}") String issuer,
                      @Value("${app.security.jwt.ttl-seconds}") long accessTtlSeconds,
                      @Value("${app.security.jwt.refresh-ttl-seconds}") long refreshTtlSeconds) {
        this.jwtEncoder = jwtEncoder;
        this.refreshTokenDecoder = refreshTokenDecoder;
        this.userRepository = userRepository;
        this.mongoTemplate = mongoTemplate;
        this.issuer = issuer;
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public TokenResponse issue(User user) {
        Instant now = Instant.now();
        return new TokenResponse(
                encode(user, ACCESS_TOKEN_TYPE, now, accessTtlSeconds),
                "Bearer",
                accessTtlSeconds,
                encode(user, REFRESH_TOKEN_TYPE, now, refreshTtlSeconds),
                refreshTtlSeconds);
    }

    public TokenResponse refresh(String refreshToken) {
        Jwt jwt;
        try {
            jwt = refreshTokenDecoder.decode(refreshToken);
        } catch (JwtException ex) {
            throw new InvalidRefreshTokenException();
        }

        String email = jwt.getSubject();
        String expectedVersion = jwt.getClaimAsString(TOKEN_VERSION_CLAIM);
        int expected;
        try {
            expected = Integer.parseInt(expectedVersion);
        } catch (NumberFormatException ex) {
            throw new InvalidRefreshTokenException();
        }

        // Rotacao atomica: incrementa tokenVersion apenas se o valor atual for igual
        // ao do token (e o usuario existir e estar ativo). Evita reuso concorrente.
        Query query = Query.query(Criteria.where("email").is(email)
                .and("active").is(true)
                .and("tokenVersion").is(expected));
        Update update = new Update().inc("tokenVersion", 1);
        UpdateResult result = mongoTemplate.updateFirst(query, update, User.class);
        if (result.getModifiedCount() == 0) {
            throw new InvalidRefreshTokenException();
        }

        User user = userRepository.findByEmail(email)
                .filter(User::isActive)
                .orElseThrow(InvalidRefreshTokenException::new);
        return issue(user);
    }

    private String encode(User user, String type, Instant issuedAt, long ttlSeconds) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.getEmail())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(ttlSeconds))
                .claim(TOKEN_TYPE_CLAIM, type)
                .claim(ROLES_CLAIM, List.of(user.getRole().name()));
        if (REFRESH_TOKEN_TYPE.equals(type)) {
            claims.claim(TOKEN_VERSION_CLAIM, String.valueOf(user.getTokenVersion()));
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
