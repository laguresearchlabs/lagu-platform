package com.lagu.platform.storage;

import java.time.Duration;
import java.util.Optional;

/**
 * Stands in when {@code platform.storage.provider} names neither backend, so a service that does
 * not need object storage can still start.
 *
 * <p>Before this, {@code provider: none} left no {@link StorageService} bean at all, and anything
 * injecting one — which is now every service with an upload path, plus {@link MediaIngest} —
 * failed to start. That turned the documented way to run without storage into a way to not run:
 * the integration test sets {@code STORAGE_PROVIDER=none} precisely because it exercises record
 * CRUD rather than file upload, and record-service could no longer boot under it.
 *
 * <p>Every method throws. The point is to let a service start without storage, not to pretend it
 * has some — a silent no-op would let an upload appear to succeed and persist a key pointing at
 * nothing. The message names the property so the fix is obvious from the stack trace alone.
 */
public class UnavailableStorageService implements StorageService {

    private static final String MESSAGE =
            "Object storage is not configured: platform.storage.provider is set to something "
            + "other than 'gcs' or 's3', so no storage backend was built. Set it (and the "
            + "matching bucket/credentials) to use file uploads.";

    @Override
    public PresignedUpload presignUpload(String key, String contentType, Duration ttl) {
        throw new StorageException(MESSAGE);
    }

    @Override
    public String presignDownload(String key, Duration ttl) {
        throw new StorageException(MESSAGE);
    }

    @Override
    public Optional<ObjectMeta> stat(String key) {
        throw new StorageException(MESSAGE);
    }

    @Override
    public void move(String fromKey, String toKey) {
        throw new StorageException(MESSAGE);
    }

    @Override
    public byte[] readAll(String key, long maxBytes) {
        throw new StorageException(MESSAGE);
    }

    @Override
    public void write(String key, byte[] content, String contentType) {
        throw new StorageException(MESSAGE);
    }

    @Override
    public byte[] readRange(String key, int length) {
        throw new StorageException(MESSAGE);
    }

    @Override
    public void delete(String key) {
        throw new StorageException(MESSAGE);
    }
}
