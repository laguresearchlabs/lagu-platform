package com.lagu.platform.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Scans uploads by streaming them to a ClamAV daemon over its {@code INSTREAM} command.
 *
 * <p>Talks the wire protocol directly rather than pulling in a client library: it is a length-
 * prefixed chunk stream and a one-line reply, and a dependency for that would be more surface
 * area than the protocol has.
 *
 * <p><b>Fails closed.</b> A clamd that is down, slow, or unreachable makes uploads fail rather
 * than pass unscanned — the whole point of scanning is that unscanned content does not reach
 * storage. That is a real availability coupling and it is deliberate: {@code platform.storage
 * .scanner.enabled=false} is how you turn it off, not a timeout.
 */
@RequiredArgsConstructor
@Slf4j
public class ClamAvMediaScanner implements MediaScanner {

    /** clamd rejects chunks above its StreamMaxLength; 64KB is well under any sane setting. */
    private static final int CHUNK_BYTES = 64 * 1024;

    private static final String OK_REPLY = "stream: OK";
    private static final String FOUND_SUFFIX = "FOUND";

    private final StorageProperties.Scanner properties;

    @Override
    public ScanResult scan(byte[] content, String key) {
        int timeoutMs = (int) properties.getTimeout().toMillis();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(properties.getHost(), properties.getPort()), timeoutMs);
            // Guards against a clamd that accepts the connection and then stalls — without it a
            // hung daemon would hold the request thread indefinitely rather than failing.
            socket.setSoTimeout(timeoutMs);

            try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                 InputStream in = socket.getInputStream()) {

                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));

                for (int offset = 0; offset < content.length; offset += CHUNK_BYTES) {
                    int length = Math.min(CHUNK_BYTES, content.length - offset);
                    out.writeInt(length);          // big-endian length prefix, as clamd expects
                    out.write(content, offset, length);
                }
                out.writeInt(0);                   // zero-length chunk terminates the stream
                out.flush();

                return interpret(readReply(in), key);
            }
        } catch (IOException e) {
            throw new StorageException(
                    "Virus scan unavailable (clamd at " + properties.getHost() + ":"
                            + properties.getPort() + "): " + e.getMessage(), e);
        }
    }

    private String readReply(InputStream in) throws IOException {
        // Replies are short and NUL-terminated in the z-prefixed protocol.
        StringBuilder reply = new StringBuilder();
        int b;
        while ((b = in.read()) != -1 && b != 0) {
            reply.append((char) b);
        }
        return reply.toString().trim();
    }

    private ScanResult interpret(String reply, String key) {
        if (OK_REPLY.equals(reply)) {
            return ScanResult.ok();
        }
        if (reply.endsWith(FOUND_SUFFIX)) {
            // "stream: Eicar-Test-Signature FOUND" — keep the signature, drop the framing.
            String signature = reply.substring(0, reply.length() - FOUND_SUFFIX.length()).trim();
            int colon = signature.indexOf(':');
            if (colon >= 0) signature = signature.substring(colon + 1).trim();
            log.warn("Malware detected in upload key={} signature={}", key, signature);
            return ScanResult.infected(signature);
        }
        // An unrecognised reply is not a pass. clamd answers ERROR for its own failures, and
        // treating anything-but-FOUND as clean would turn every one of those into a silent
        // bypass — the exact failure mode a scanner must not have.
        throw new StorageException("Unexpected clamd reply for " + key + ": " + reply);
    }
}
