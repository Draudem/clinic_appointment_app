package com.clinic.backend.mis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MisProperties.class)
public class MisConfig {

    @Bean
    public MisClient misClient(MisProperties props) {
        if (props.isEnabled() && props.getToken() != null && !props.getToken().isBlank()) {
            return new RestMisClient(props);
        }
        return new DisabledMisClient();
    }
}