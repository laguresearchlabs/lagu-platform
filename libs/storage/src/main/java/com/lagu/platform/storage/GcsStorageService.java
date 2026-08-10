package com.lagu.platform.storage;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
// Deliberately not importing com.google.cloud.storage.StorageException — the unqualified name
// must resolve to this package's StorageException, which is what callers catch.
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
@Slf4j
public class GcsStorageService implements StorageService {

    private static final List<String> SIGN_SCOPE =
            List.of("https://www.googleapis.com/auth/devstorage.read_write");

    private final Storage storage;
    private final GoogleCredentials credentials;
    private final StorageProperties properties;

    @Override
    public PresignedUpload presignUpload(String key, String contentType, Duration ttl) {
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId(key))
                .setContentType(contentType)
                .build();

        // withExtHeaders binds Content-Type into the signature: the client must send exactly
        // this header or GCS rejects the PUT. Without it the signature would cover the key
        // alone and a client could upload anything under any type.
        URL url = sign(blobInfo, ttl,
                Storage.SignUrlOption.httpMethod(com.google.cloud.storage.HttpMethod.PUT),
                Storage.SignUrlOption.withExtHeaders(Map.of("Content-Type", contentType)));

        return new PresignedUpload(url.toString(), key, contentType, Instant.now().plus(ttl));
    }

    @Override
    public String presignDownload(String key, Duration ttl) {
        return sign(BlobInfo.newBuilder(blobId(key)).build(), ttl).toString();
    }

    @Override
    public Optional<ObjectMeta> stat(String key) {
        try {
            var blob = storage.get(blobId(key));
            if (blob == null || !blob.exists()) {
                return Optional.empty();
            }
            return Optional.of(new ObjectMeta(
                    key,
                    blob.getSize() == null ? 0L : blob.getSize(),
                    blob.getContentType(),
                    blob.getCreateTimeOffsetDateTime() == null
                            ? null
                            : blob.getCreateTimeOffsetDateTime().toInstant()));
        } catch (RuntimeException e) {
            throw new StorageException("Failed to stat object " + key, e);
        }
    }

    @Override
    public byte[] readRange(String key, int length) {
        try (var reader = storage.reader(blobId(key))) {
            var buffer = java.nio.ByteBuffer.allocate(length);
            // A single read() can return short without meaning EOF, so loop until the buffer
            // fills or the channel actually ends — otherwise a truncated header would look
            // like a signature mismatch and reject a legitimate upload.
            while (buffer.hasRemaining() && reader.read(buffer) > 0) {
                // keep reading
            }
            byte[] out = new byte[buffer.position()];
            buffer.flip();
            buffer.get(out);
            return out;
        } catch (Exception e) {
            throw new StorageException("Failed to read object " + key, e);
        }
    }

    @Override
    public void move(String fromKey, String toKey) {
        try {
            // copyTo is server-side: GCS does the transfer, so a 25MB photo does not round-trip
            // through this pod just to change its key.
            storage.copy(Storage.CopyRequest.of(blobId(fromKey), blobId(toKey))).getResult();
            storage.delete(blobId(fromKey));
        } catch (RuntimeException e) {
            throw new StorageException("Failed to move object " + fromKey + " to " + toKey, e);
        }
    }

    @Override
    public byte[] readAll(String key, long maxBytes) {
        var blob = storage.get(blobId(key));
        if (blob == null || !blob.exists()) {
            throw new StorageException("Object not found: " + key);
        }
        long size = blob.getSize() == null ? 0L : blob.getSize();
        // Checked before reading, not while reading: the point is to never allocate for an
        // object this process was not prepared to hold.
        if (size > maxBytes) {
            throw new StorageException(
                    "Object " + key + " is " + size + " bytes, above the " + maxBytes + " limit");
        }
        try {
            return blob.getContent();
        } catch (RuntimeException e) {
            throw new StorageException("Failed to read object " + key, e);
        }
    }

    @Override
    public void write(String key, byte[] content, String contentType) {
        try {
            storage.create(BlobInfo.newBuilder(blobId(key)).setContentType(contentType).build(),
                    content);
        } catch (RuntimeException e) {
            throw new StorageException("Failed to write object " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            storage.delete(blobId(key));
        } catch (RuntimeException e) {
            throw new StorageException("Failed to delete object " + key, e);
        }
    }

    private BlobId blobId(String key) {
        return BlobId.of(properties.getGcs().getBucket(), key);
    }

    /**
     * V4 signing, carried over from image-service's {@code GcpStorageService.generateSignedUrl}.
     *
     * <p>A mounted service-account key can sign locally. Under Workload Identity — the target
     * state, since spreading bucket access to several services must not mean spreading key
     * files — there is no private key in the pod, so signing goes through IAM
     * {@code signBlob} while impersonating the configured service account.
     */
    private URL sign(BlobInfo blobInfo, Duration ttl, Storage.SignUrlOption... extraOptions) {
        Storage.SignUrlOption[] options = new Storage.SignUrlOption[extraOptions.length + 2];
        System.arraycopy(extraOptions, 0, options, 0, extraOptions.length);
        options[extraOptions.length] = Storage.SignUrlOption.withV4Signature();
        options[extraOptions.length + 1] = Storage.SignUrlOption.signWith(signingCredentials());

        try {
            return storage.signUrl(blobInfo, ttl.toSeconds(), java.util.concurrent.TimeUnit.SECONDS, options);
        } catch (RuntimeException e) {
            throw new StorageException("Failed to sign URL for " + blobInfo.getBlobId().getName(), e);
        }
    }

    private com.google.auth.ServiceAccountSigner signingCredentials() {
        if (credentials instanceof ServiceAccountCredentials sa) {
            return sa;
        }
        String email = properties.getGcs().getServiceAccountEmail();
        if (email == null || email.isBlank()) {
            throw new StorageException(
                    "Cannot sign URLs: credentials are not a service-account key and " +
                    "platform.storage.gcs.service-account-email is unset. Set it to the service " +
                    "account bound via Workload Identity (it needs " +
                    "roles/iam.serviceAccountTokenCreator on itself to use IAM signBlob).");
        }
        return ImpersonatedCredentials.create(credentials, email, null, SIGN_SCOPE, 300);
    }
}
