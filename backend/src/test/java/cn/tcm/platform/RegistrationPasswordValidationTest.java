package cn.tcm.platform;

import cn.tcm.platform.common.ApiException;
import cn.tcm.platform.identity.api.IdentityDtos;
import cn.tcm.platform.identity.application.IdentityService;
import cn.tcm.platform.identity.infrastructure.IdentityMapper;
import cn.tcm.platform.identity.infrastructure.SecurityProperties;
import cn.tcm.platform.identity.infrastructure.TokenService;
import jakarta.validation.Validation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistrationPasswordValidationTest {
    @ParameterizedTest
    @ValueSource(strings={"Abcdefgh12","Abcdefghi!","Admin12345!","Abcdefghij1!","Abcdefg'12","Abcdefg\"12","Abcdefg\\12","Abcdefg[12"})
    void acceptsTenToTwelveAsciiCharactersWithBothLetterCasesAndDigitOrSymbol(String password) {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new IdentityDtos.Register("learner",password,"学习者"))).isEmpty();
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings={"","Abcdefg12","Abcdefghijk1!","abcdefgh12","ABCDEFGH12","Abcdefghij","1234567890","!@#$%^&*()",
            "Abcdef12中文","Abcdef12あい","Abcdef12مرح","Abcdefgh１!","Abcdef12😀","Abcdef12 !","Abcdef12\t!","Abcdef12\n!","Abcdef12\u00a0!","Abcdef12\u200b!","Abcdefgh12\n","Abcdefgh12\r"})
    void rejectsInvalidPasswordAtRequestBoundary(String password) {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new IdentityDtos.Register("learner",password,"学习者")))
                    .anyMatch(v->v.getPropertyPath().toString().equals("password"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"Abcdefg12","Abcdefghijk1!","abcdefgh12","Abcdef12中文","Abcdef12 !"})
    void directServiceCallsRejectInvalidPasswordBeforeAccessingDatabase(String password) {
        IdentityMapper mapper=mock(IdentityMapper.class);
        var service=new IdentityService(mapper,mock(PasswordEncoder.class),mock(TokenService.class),mock(SecurityProperties.class),java.time.Clock.systemUTC(),
                mock(cn.tcm.platform.community.infrastructure.CommunitySectionRepository.class));
        assertThatThrownBy(()->service.register(new IdentityDtos.Register("learner",password,"学习者")))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(400));
        verifyNoInteractions(mapper);
    }
}
