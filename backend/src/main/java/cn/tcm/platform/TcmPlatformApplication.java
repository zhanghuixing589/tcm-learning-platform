package cn.tcm.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import java.util.Arrays;
import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TcmPlatformApplication {
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ConfigurableApplicationContext context=SpringApplication.run(TcmPlatformApplication.class,args);
        if(Arrays.asList(args).contains("--bootstrap-admin")) context.close();
    }
}
