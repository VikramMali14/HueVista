package com.gridstore.huevista.image.config;

import com.gridstore.huevista.image.service.LocalStorageService;
import com.gridstore.huevista.image.service.StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {

    @Bean
    @ConditionalOnMissingBean(StorageService.class)
    public StorageService localStorageService(
            @Value("${app.upload.storage-path:/tmp/huevista/uploads}") String storagePath) {
        return new LocalStorageService(storagePath);
    }
}
