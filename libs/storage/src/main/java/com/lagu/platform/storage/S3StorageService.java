package com.lagu.platform.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@RequiredArgsConstructor
@Slf4j
public class S3StorageService implements StorageService {

    private final S3Client client;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    @Override
    public PresignedUpload presignUpload(String key, String contentType, Duration ttl) {
        // contentType on the PutObjectRequest is signed, so the client must send a matching
        // Content-Type header — same binding as the GCS backend's withExtHeaders.
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket())
                .key(key)
                .contentType(contentType)
                .build();

        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(put)
                .build());

        return new PresignedUpload(presigned.url().toString(), key, contentType, Instant.now().plus(ttl));
    }

    @Override
    public String presignDownload(String key, Duration ttl) {
        var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket()).key(key).build())
                .build());
        return presigned.url().toString();
    }

    @Override
    public Optional<ObjectMeta> stat(String key) {
        try {
            HeadObjectResponse head = client.headObject(
                    HeadObjectRequest.builder().bucket(bucket()).key(key).build());
            return Optional.of(new ObjectMeta(
                    key, head.contentLength(), head.contentType(), head.lastModified()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            throw new StorageException("Failed to stat object " + key, e);
        }
    }

    @Override
    public byte[] readRange(String key, int length) {
        try {
            // Range is inclusive at both ends, so length bytes is 0..length-1. S3 returns
            // whatever exists when the object is shorter rather than erroring.
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket())
                    .key(key)
                    .range("bytes=0-" + (length - 1))
                    .build();
            ResponseBytes<?> bytes = client.getObjectAsBytes(request);
            return bytes.asByteArray();
        } catch (NoSuchKeyException e) {
            throw new StorageException("Object not found: " + key, e);
        } catch (RuntimeException e) {
            throw new StorageException("Failed to read object " + key, e);
        }
    }

    @Override
    public void move(String fromKey, String toKey) {
        try {
            // Server-side copy: the bytes stay in S3 rather than round-tripping through here.
            client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket()).sourceKey(fromKey)
                    .destinationBucket(bucket()).destinationKey(toKey)
                    .build());
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket()).key(fromKey).build());
        } catch (RuntimeException e) {
            throw new StorageException("Failed to move object " + fromKey + " to " + toKey, e);
        }
    }

    @Override
    public byte[] readAll(String key, long maxBytes) {
        // HEAD first so an oversized object is refused before anything is allocated for it.
        ObjectMeta meta = stat(key)
                .orElseThrow(() -> new StorageException("Object not found: " + key));
        if (meta.sizeBytes() > maxBytes) {
            throw new StorageException("Object " + key + " is " + meta.sizeBytes()
                    + " bytes, above the " + maxBytes + " limit");
        }
        try {
            return client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket()).key(key).build()).asByteArray();
        } catch (RuntimeException e) {
            throw new StorageException("Failed to read object " + key, e);
        }
    }

    @Override
    public void write(String key, byte[] content, String contentType) {
        try {
            client.putObject(
                    PutObjectRequest.builder().bucket(bucket()).key(key).contentType(contentType).build(),
                    RequestBody.fromBytes(content));
        } catch (RuntimeException e) {
            throw new StorageException("Failed to write object " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket()).key(key).build());
        } catch (RuntimeException e) {
            throw new StorageException("Failed to delete object " + key, e);
        }
    }

    private String bucket() {
        return properties.getS3().getBucket();
    }
}
