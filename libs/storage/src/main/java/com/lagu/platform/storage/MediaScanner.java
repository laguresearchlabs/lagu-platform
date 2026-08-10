package com.lagu.platform.storage;

/**
 * Scans an uploaded object for malware before it is persisted.
 *
 * <p>{@link ContentTypeSniffer} answers "are these bytes the format they claim to be", which is a
 * different question from "are these bytes safe". A file with a perfectly valid PDF header can
 * still carry a payload, and vendor-uploaded documents are opened by staff — so the signature
 * check was never a substitute for this.
 */
public interface MediaScanner {

    /**
     * @param content the object's full bytes
     * @param key     the object's key, for logging — never trusted as an identifier of content
     */
    ScanResult scan(byte[] content, String key);

    /**
     * @param signature what the scanner matched, when infected; null otherwise
     */
    record ScanResult(boolean clean, String signature) {

        /** Named {@code ok} rather than {@code clean} because the record already generates a
         *  {@code clean()} accessor for its component. */
        public static ScanResult ok() {
            return new ScanResult(true, null);
        }

        public static ScanResult infected(String signature) {
            return new ScanResult(false, signature);
        }
    }
}
