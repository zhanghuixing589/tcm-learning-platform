package cn.tcm.platform;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.identity.application.IdentityService;
import cn.tcm.platform.identity.domain.RefreshToken;
import cn.tcm.platform.identity.domain.UserAccount;
import cn.tcm.platform.identity.infrastructure.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionExpirationTest {
    private static final Instant DEADLINE=Instant.parse("2026-11-18T12:00:00Z");
    private final IdentityMapper mapper=mock(IdentityMapper.class);
    private final TokenService tokens=new TokenService(new SecurityProperties("test-signing-secret-at-least-32-bytes",List.of("http://localhost:5173"),false,900,7,30));
    private IdentityService service(Instant now) {
        UserAccount user=new UserAccount();user.id=1L;user.status="ACTIVE";user.credentialVersion=0;
        RefreshToken previous=new RefreshToken();previous.id=1L;previous.userId=1L;
        previous.familyKey="fixed-family";previous.tokenHash=tokens.hash("old-refresh");
        previous.absoluteExpiresAt=LocalDateTime.ofInstant(DEADLINE,ZoneOffset.UTC);
        // Deliberately later than the deadline to verify the independent absolute-expiry guard.
        previous.expiresAt=previous.absoluteExpiresAt.plusDays(7);
        RefreshToken next=new RefreshToken();next.id=2L;
        when(mapper.token(anyString())).thenReturn(previous,next);
        when(mapper.lockUser(1L)).thenReturn(user);
        when(mapper.lockToken(previous.tokenHash)).thenReturn(previous);
        return new IdentityService(mapper,mock(PasswordEncoder.class),tokens,
                new SecurityProperties("test-signing-secret-at-least-32-bytes",List.of("http://localhost:5173"),false,900,7,30),
                Clock.fixed(now,ZoneOffset.UTC),mock(cn.tcm.platform.community.infrastructure.CommunitySectionRepository.class));
    }
    @ParameterizedTest @ValueSource(longs={0,1,86400})
    void atOrAfterAbsoluteDeadlineRejectsEvenAnOtherwiseUnexpiredRefresh(long secondsAfter) {
        var service=service(DEADLINE.plusSeconds(secondsAfter));
        assertThatThrownBy(()->service.refresh("old-refresh")).isInstanceOf(ApiException.class);
        verify(mapper).revokeFamily("fixed-family");
        verify(mapper,never()).addToken(any());
    }
    @Test void oneSecondBeforeDeadlineIssuesOnlyOneSecondAndPreservesDeadline() {
        var issued=service(DEADLINE.minusSeconds(1)).refresh("old-refresh");
        assertThat(issued.body().expiresIn()).isEqualTo(1);
        assertThat(issued.refreshMaxAgeSeconds()).isEqualTo(1);
        var inserted=ArgumentCaptor.forClass(RefreshToken.class);
        verify(mapper).addToken(inserted.capture());
        assertThat(inserted.getValue().absoluteExpiresAt.toInstant(ZoneOffset.UTC)).isEqualTo(DEADLINE);
        assertThat(inserted.getValue().expiresAt.toInstant(ZoneOffset.UTC)).isEqualTo(DEADLINE);
        verify(mapper,never()).revokeFamily(anyString());
    }
}
