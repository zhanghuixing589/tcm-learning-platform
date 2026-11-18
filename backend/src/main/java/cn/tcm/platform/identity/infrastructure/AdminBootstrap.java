package cn.tcm.platform.identity.infrastructure;

import cn.tcm.platform.identity.api.IdentityDtos;
import cn.tcm.platform.identity.application.IdentityService;
import jakarta.validation.Validator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final Environment env;
    private final IdentityService service;
    private final IdentityMapper mapper;
    private final Validator validator;
    public AdminBootstrap(Environment env,IdentityService service,IdentityMapper mapper,Validator validator) {
        this.env=env;this.service=service;this.mapper=mapper;this.validator=validator;
    }
    @Override @Transactional public void run(ApplicationArguments args) {
        if(!args.containsOption("bootstrap-admin"))return;
        String name=env.getRequiredProperty("BOOTSTRAP_ADMIN_USERNAME"), password=env.getRequiredProperty("BOOTSTRAP_ADMIN_PASSWORD");
        if(mapper.byUsername(name)!=null)throw new IllegalStateException("Bootstrap only creates a new administrator; username already exists");
        var input=new IdentityDtos.Register(name,password,"平台管理员");
        if(!validator.validate(input).isEmpty())throw new IllegalArgumentException("Bootstrap username/password does not meet account requirements");
        var user=service.register(input);mapper.grant(Long.parseLong(user.id()),"ADMIN",null);
        System.out.println("Administrator created. No professional reviewer role was granted.");
    }
}
