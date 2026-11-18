package cn.tcm.platform.identity.infrastructure;

import cn.tcm.platform.identity.domain.UserAccount;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class TokenService {
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final SecureRandom random=new SecureRandom();
    public TokenService(SecurityProperties properties) {
        var key=new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256");
        encoder=new NimbusJwtEncoder(new ImmutableSecret<>(key));
        var jwtDecoder=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("tcm-platform"));
        decoder=jwtDecoder;
    }
    public String access(UserAccount user,String family,Instant now,Instant expiresAt) {
        var claims=JwtClaimsSet.builder().issuer("tcm-platform").subject(user.id.toString()).issuedAt(now)
                .expiresAt(expiresAt).claim("cv",user.credentialVersion).claim("sid",family).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    public Jwt decode(String raw) { return decoder.decode(raw); }
    public String refresh() { byte[] bytes=new byte[48]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
