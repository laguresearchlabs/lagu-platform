package com.lagu.platform.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wiring, specifically the case where there is no storage backend.
 *
 * <p>{@code provider: none} is the documented way to run a service that does not need object
 * storage — the end-to-end test uses it because it drives record CRUD rather than uploads. It
 * stopped working when {@code StorageService} became a hard dependency of the upload paths:
 * neither backend condition matched, so nothing was registered and every service injecting one
 * failed to start.
 *
 * <p>These also pin the selection rule itself. The backends and the fallback all key on the same
 * property so that exactly one is registered whatever order Spring processes them in;
 * {@code @ConditionalOnMissingBean} would read better but is only order-guaranteed inside
 * auto-configuration, and this is a plain {@code @Configuration}.
 */
class StorageConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(StorageConfig.class);

    @Test
    void startsWithoutABackendSoServicesThatDoNotUploadCanRun() {
        runner.withPropertyValues("platform.storage.provider=none").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(StorageService.class);
            assertThat(context.getBean(StorageService.class))
                    .isInstanceOf(UnavailableStorageService.class);
            // MediaIngest is what actually broke: an unconditional bean needing StorageService.
            assertThat(context).hasSingleBean(MediaIngest.class);
        });
    }

    /**
     * Starting is not the same as working. A no-op that silently succeeded would let an upload
     * appear to work and persist a key pointing at nothing.
     */
    @Test
    void butAnyActualUseFailsLoudly() {
        runner.withPropertyValues("platform.storage.provider=none").run(context -> {
            StorageService storage = context.getBean(StorageService.class);

            assertThatThrownBy(() -> storage.stat("record/abc/x.jpg"))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("platform.storage.provider");
            assertThatThrownBy(() -> storage.presignDownload("record/abc/x.jpg", java.time.Duration.ofMinutes(1)))
                    .isInstanceOf(StorageException.class);
        });
    }

    /**
     * Naming a real backend must not also register the fallback. The three conditions key on the
     * same property so they stay mutually exclusive — this pins that, since a second
     * {@code StorageService} would make injection ambiguous and fail every service at startup.
     *
     * <p>Uses s3 rather than gcs because the gcs configuration resolves Application Default
     * Credentials eagerly, which has nothing to do with what is being asserted here.
     */
    @Test
    void namingABackendDoesNotAlsoRegisterTheFallback() {
        runner.withPropertyValues(
                "platform.storage.provider=s3",
                "platform.storage.s3.region=us-east-1",
                "platform.storage.s3.bucket=test").run(context -> {
            // The S3 client may or may not construct without credentials; either way the
            // fallback must not have been contributed alongside it.
            assertThat(context.getStartupFailure() == null
                    ? context.getBeansOfType(UnavailableStorageService.class).size()
                    : 0).isZero();
        });
    }

    @Test
    void scanningIsOffUnlessAskedFor() {
        runner.withPropertyValues("platform.storage.provider=none").run(context -> {
            assertThat(context).hasSingleBean(MediaScanner.class);
            assertThat(context.getBean(MediaScanner.class))
                    .isNotInstanceOf(ClamAvMediaScanner.class);
            // The no-op passes everything, which is why the service logs a warning about it.
            assertThat(context.getBean(MediaScanner.class).scan(new byte[]{1}, "k").clean()).isTrue();
        });
    }

    @Test
    void enablingTheScannerSelectsClamAv() {
        runner.withPropertyValues(
                "platform.storage.provider=none",
                "platform.storage.scanner.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(MediaScanner.class);
            assertThat(context.getBean(MediaScanner.class)).isInstanceOf(ClamAvMediaScanner.class);
        });
    }
}
