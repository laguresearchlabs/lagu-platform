package com.lagu.platform.storage;

import com.lagu.platform.common.exception.PlatformException;
import org.springframework.http.HttpStatus;

/**
 * A storage backend failed. Distinct from {@code ValidationException}: this means the bucket
 * call itself errored, not that the caller's request was bad.
 */
public class StorageException extends PlatformException {

    public StorageException(String message, Throwable cause) {
        super("STORAGE_ERROR", message, HttpStatus.INTERNAL_SERVER_ERROR);
        initCause(cause);
    }

    public StorageException(String message) {
        super("STORAGE_ERROR", message, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
