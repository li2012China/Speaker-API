package net.speakerapi.internal.tar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal, dependency-free tar extractor (ustar + GNU long-name + PAX-aware).
 *
 * <p>Why this exists: commons-compress pulls in commons-io / commons-lang3 /
 * commons-codec transitively, which collided with the ModrinthApp launcher on
 * the JPMS module path and forced fragile class relocation. A bundled tar.bz2
 * extraction only needs bzip2 decompression (see {@code net.speakerapi.internal.bzip2})
 * plus a 512-byte-block tar walk, so we vendor exactly that and keep the mod
 * fully self-contained.</p>
 */
public final class TarExtractor {

    private static final int BLOCK = 512;

    private TarExtractor() {}

    public static void extract(InputStream tarIn, Path dest) throws IOException {
        byte[] hdr = new byte[BLOCK];
        String pendingLongName = null;

        while (true) {
            int got = readFully(tarIn, hdr);
            if (got < BLOCK) {
                break; // truncated or clean EOF
            }
            if (isZeroBlock(hdr)) {
                break; // end-of-archive marker
            }

            String name = readString(hdr, 0, 100).trim();
            long size = parseSize(hdr, 124, 12);
            byte typeFlag = hdr[156];
            String magic = readString(hdr, 257, 5);
            String prefix = magic.startsWith("ustar") ? readString(hdr, 345, 155).trim() : "";

            if (typeFlag == 'L') { // GNU long name: next 'size' bytes are the real name
                pendingLongName = readDataAsString(tarIn, size);
                skipPadding(tarIn, size);
                continue;
            }
            if (typeFlag == 'x' || typeFlag == 'g') { // PAX extended / global header: ignore
                skipData(tarIn, size);
                continue;
            }

            String fullName = (pendingLongName != null) ? pendingLongName : ((prefix.isEmpty() ? "" : prefix + "/") + name);
            pendingLongName = null;
            fullName = fullName.replace("\0", "").trim();
            if (fullName.startsWith("./")) {
                fullName = fullName.substring(2);
            }

            // 去掉首段顶层目录 (如 kokoro-multi-lang-v1_0/), 使 model.onnx 等落到 dest 根。
            int slash = fullName.indexOf('/');
            String rel = (slash > 0) ? fullName.substring(slash + 1) : fullName;
            if (rel.isEmpty()) {
                skipData(tarIn, size);
                continue;
            }

            Path out = dest.resolve(rel);
            if (typeFlag == '5') { // directory
                Files.createDirectories(out);
            } else { // regular file (type '0' or '\0'); copy data
                Path parent = out.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (java.io.OutputStream os = Files.newOutputStream(out)) {
                    long remaining = size;
                    byte[] buf = new byte[1 << 16];
                    while (remaining > 0) {
                        int n = tarIn.read(buf, 0, (int) Math.min(buf.length, remaining));
                        if (n < 0) {
                            break;
                        }
                        os.write(buf, 0, n);
                        remaining -= n;
                    }
                }
            }
            skipPadding(tarIn, size);
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    private static String readString(byte[] b, int off, int len) {
        int end = off;
        int max = Math.min(off + len, b.length);
        while (end < max && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Parse a tar numeric field: octal text, or base-256 if high bit set. */
    private static long parseSize(byte[] b, int off, int len) {
        if (len > 0 && (b[off] & 0x80) != 0) { // base-256
            long val = 0;
            for (int i = 0; i < len; i++) {
                val = (val << 8) | (b[off + i] & 0xFF);
            }
            return val;
        }
        long val = 0;
        for (int i = 0; i < len; i++) {
            byte c = b[off + i];
            if (c == 0 || c == ' ') {
                continue;
            }
            if (c >= '0' && c <= '7') {
                val = (val << 3) | (c - '0');
            } else {
                break;
            }
        }
        return val;
    }

    private static String readDataAsString(InputStream in, long size) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        long remaining = size;
        byte[] buf = new byte[4096];
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) {
                break;
            }
            bos.write(buf, 0, n);
            remaining -= n;
        }
        return bos.toString("UTF-8").replace("\0", "").trim();
    }

    private static void skipData(InputStream in, long size) throws IOException {
        long remaining = size;
        byte[] buf = new byte[8192];
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) {
                break;
            }
            remaining -= n;
        }
        skipPadding(in, size);
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long padded = ((size + BLOCK - 1) / BLOCK) * BLOCK;
        long toSkip = padded - size;
        while (toSkip > 0) {
            long n = in.skip(toSkip);
            if (n <= 0) {
                // fall back to reading
                int r = in.read();
                if (r < 0) {
                    break;
                }
                toSkip -= 1;
            } else {
                toSkip -= n;
            }
        }
    }
}
