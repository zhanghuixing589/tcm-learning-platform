package cn.tcm.platform;

import cn.tcm.platform.identity.api.IdentityDtos;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.assertj.core.api.Assertions.assertThat;

class RoleValidationTest {
    @Test void nullRoleIsRejectedBeforeAnyRolesCanBeChanged() {
        var roles=new HashSet<String>();roles.add(null);
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new IdentityDtos.Roles(roles))).isNotEmpty();
        }
    }
}
