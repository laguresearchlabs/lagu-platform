package com.lagu.platform.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the INSTREAM exchange against a stand-in daemon.
 *
 * <p>What is worth testing here is not that ClamAV works but that this client fails in the right
 * direction: an unreachable or confused daemon must stop an upload, never wave it through. A
 * scanner that silently passes on error is worse than no scanner, because it looks like one.
 */
class ClamAvMediaScannerTest {

    private ServerSocket server;
    private Thread serverThread;

    @AfterEach
    void tearDown() throws IOException {
        if (server != null && !server.isClosed()) server.close();
        if (serverThread != null) serverThread.interrupt();
    }

    /** A fake clamd that reads a whole INSTREAM upload and answers with {@code reply}. */
    private StorageProperties.Scanner fakeClamd(String reply, List<byte[]> received) throws Exception {
        server = new ServerSocket(0);
        CountDownLatch listening = new CountDownLatch(1);

        serverThread = new Thread(() -> {
            listening.countDown();
            try (Socket socket = server.accept();
                 DataInputStream in = new DataInputStream(socket.getInputStream());
                 OutputStream out = socket.getOutputStream()) {

                // "zINSTREAM\0"
                StringBuilder command = new StringBuilder();
                int b;
                while ((b = in.read()) != -1 && b != 0) command.append((char) b);

                java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
                while (true) {
                    int length = in.readInt();
                    if (length == 0) break;          // terminating zero-length chunk
                    byte[] chunk = new byte[length];
                    in.readFully(chunk);
                    body.write(chunk);
                }
                received.add(body.toByteArray());

                out.write(reply.getBytes(StandardCharsets.US_ASCII));
                out.write(0);
                out.flush();
            } catch (IOException ignored) {
                // Socket closed by the test; nothing to do.
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        listening.await(5, TimeUnit.SECONDS);

        StorageProperties.Scanner properties = new StorageProperties.Scanner();
        properties.setHost("127.0.0.1");
        properties.setPort(server.getLocalPort());
        properties.setTimeout(Duration.ofSeconds(5));
        return properties;
    }

    @Test
    void passesCleanContentThrough() throws Exception {
        List<byte[]> received = new ArrayList<>();
        var scanner = new ClamAvMediaScanner(fakeClamd("stream: OK", received));

        var result = scanner.scan("harmless bytes".getBytes(), "record/abc/photo.jpg");

        assertThat(result.clean()).isTrue();
        assertThat(result.signature()).isNull();
        // The daemon must receive the whole object, not a truncated prefix.
        assertThat(received).singleElement().isEqualTo("harmless bytes".getBytes());
    }

    @Test
    void reportsTheSignatureWhenSomethingIsFound() throws Exception {
        var scanner = new ClamAvMediaScanner(
                fakeClamd("stream: Eicar-Test-Signature FOUND", new ArrayList<>()));

        var result = scanner.scan("bad bytes".getBytes(), "record/abc/photo.jpg");

        assertThat(result.clean()).isFalse();
        assertThat(result.signature()).isEqualTo("Eicar-Test-Signature");
    }

    /** Content larger than one chunk still has to arrive intact and in order. */
    @Test
    void streamsContentSpanningManyChunks() throws Exception {
        List<byte[]> received = new ArrayList<>();
        var scanner = new ClamAvMediaScanner(fakeClamd("stream: OK", received));

        byte[] large = new byte[200 * 1024];
        for (int i = 0; i < large.length; i++) large[i] = (byte) (i % 251);

        assertThat(scanner.scan(large, "record/abc/big.jpg").clean()).isTrue();
        assertThat(received).singleElement().isEqualTo(large);
    }

    /**
     * The failure mode that matters. clamd answers ERROR for its own problems, and anything not
     * recognised as either OK or FOUND must stop the upload rather than be read as a pass.
     */
    @Test
    void treatsAnUnrecognisedReplyAsAFailureNotAPass() throws Exception {
        var scanner = new ClamAvMediaScanner(
                fakeClamd("ERROR: size limit exceeded", new ArrayList<>()));

        assertThatThrownBy(() -> scanner.scan("bytes".getBytes(), "record/abc/photo.jpg"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Unexpected clamd reply");
    }

    /** Fails closed: no daemon means no upload, not an unscanned one. */
    @Test
    void failsWhenTheDaemonIsUnreachable() throws IOException {
        // Bind and immediately release, so the port is almost certainly nobody's.
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }

        StorageProperties.Scanner properties = new StorageProperties.Scanner();
        properties.setHost("127.0.0.1");
        properties.setPort(deadPort);
        properties.setTimeout(Duration.ofMillis(500));

        assertThatThrownBy(() ->
                new ClamAvMediaScanner(properties).scan("bytes".getBytes(), "record/abc/photo.jpg"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Virus scan unavailable");
    }
}
