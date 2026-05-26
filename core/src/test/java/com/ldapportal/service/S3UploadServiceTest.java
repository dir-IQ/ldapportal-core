// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.entity.ApplicationSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3UploadServiceTest {

    @Mock private ApplicationSettingsService appSettingsService;
    @Mock private EncryptionService encryptionService;

    private S3UploadService service;

    @BeforeEach
    void setUp() {
        service = new S3UploadService(appSettingsService, encryptionService);
    }

    @Test
    void isConfigured_allFieldsSet_returnsTrue() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setS3EndpointUrl("https://s3.us-east-1.amazonaws.com");
        settings.setS3BucketName("my-bucket");
        settings.setS3AccessKey("AKIAIOSFODNN7EXAMPLE");
        settings.setS3SecretKeyEncrypted("encrypted-secret");
        when(appSettingsService.getEntity()).thenReturn(settings);

        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void isConfigured_missingEndpoint_returnsFalse() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setS3BucketName("my-bucket");
        settings.setS3AccessKey("AKIAIOSFODNN7EXAMPLE");
        settings.setS3SecretKeyEncrypted("encrypted-secret");
        when(appSettingsService.getEntity()).thenReturn(settings);

        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_missingBucket_returnsFalse() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setS3EndpointUrl("https://s3.us-east-1.amazonaws.com");
        settings.setS3AccessKey("AKIAIOSFODNN7EXAMPLE");
        settings.setS3SecretKeyEncrypted("encrypted-secret");
        when(appSettingsService.getEntity()).thenReturn(settings);

        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_missingAccessKey_returnsFalse() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setS3EndpointUrl("https://s3.us-east-1.amazonaws.com");
        settings.setS3BucketName("my-bucket");
        settings.setS3SecretKeyEncrypted("encrypted-secret");
        when(appSettingsService.getEntity()).thenReturn(settings);

        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_missingSecretKey_returnsFalse() {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setS3EndpointUrl("https://s3.us-east-1.amazonaws.com");
        settings.setS3BucketName("my-bucket");
        settings.setS3AccessKey("AKIAIOSFODNN7EXAMPLE");
        when(appSettingsService.getEntity()).thenReturn(settings);

        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_noSettings_returnsFalse() {
        when(appSettingsService.getEntity()).thenReturn(new ApplicationSettings());

        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void upload_notConfigured_throwsIllegalState() {
        ApplicationSettings settings = new ApplicationSettings();
        when(appSettingsService.getEntity()).thenReturn(settings);

        assertThatThrownBy(() -> service.upload("test.csv", "data".getBytes(), "text/csv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }
}
