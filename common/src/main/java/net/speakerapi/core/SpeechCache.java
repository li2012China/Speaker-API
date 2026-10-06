package net.speakerapi.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Sentence-level LRU cache of synthesized speech as 16-bit PCM WAV files on disk.
 *
 * <p>Key = SHA-256 of {@code text|voiceId|speed}. When a key exists we skip
 * synthesis entirely and replay the cached WAV. Disk files are trimmed to
 * {@code maxEntries} (oldest-modified first).</p>
 */
public final class SpeechCache {

    private final Path cacheDir;
    private final int maxEntries;
    private final Map<String, Long> accessOrder = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > maxEntries;
        }
    };

    public SpeechCache(Path cacheDir, int maxEntries) {
        this.cacheDir = cacheDir;
        this.maxEntries = maxEntries;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private String key(String text, String voiceId, float speed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((text + "|" + voiceId + "|" + speed).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((text + voiceId + speed).hashCode());
        }
    }

    public Path fileFor(String text, String voiceId, float speed) {
        return cacheDir.resolve(key(text, voiceId, speed) + ".wav");
    }

    public boolean has(String text, String voiceId, float speed) {
        return Files.exists(fileFor(text, voiceId, speed));
    }

    public AudioData load(String text, String voiceId, float speed) {
        Path f = fileFor(text, voiceId, speed);
        if (!Files.exists(f)) {
            return null;
        }
        try {
            byte[] all = Files.readAllBytes(f);
            if (all.length < 44) {
                return null;
            }
            int sampleRate = readLE32(all, 24);
            int dataLen = readLE32(all, 40);
            int off = 44;
            int n = dataLen / 2;
            float[] samples = new float[n];
            for (int i = 0; i < n; i++) {
                short s = (short) ((all[off] & 0xff) | ((all[off + 1] & 0xff) << 8));
                samples[i] = s / 32768f;
                off += 2;
            }
            accessOrder.put(key(text, voiceId, speed), System.nanoTime());
            return new AudioData(samples, sampleRate, voiceId);
        } catch (IOException e) {
            return null;
        }
    }

    public void store(AudioData audio, String text, float speed) {
        Path f = fileFor(text, audio.voiceId(), speed);
        try {
            Files.write(f, encodeWav(audio));
            accessOrder.put(key(text, audio.voiceId(), speed), System.nanoTime());
            enforceLru();
        } catch (IOException e) {
            // best effort
        }
    }

    private void enforceLru() {
        try (Stream<Path> s = Files.list(cacheDir)) {
            List<Path> files = s.filter(p -> p.toString().endsWith(".wav")).toList();
            if (files.size() <= maxEntries) {
                return;
            }
            files.stream()
                    .sorted(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0;
                        }
                    }))
                    .limit(files.size() - maxEntries)
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best effort
                        }
                    });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static byte[] encodeWav(AudioData audio) {
        float[] samples = audio.samples();
        int dataLen = samples.length * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataLen);
        try {
            out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            writeLE32(out, 36 + dataLen);
            out.write("WAVE".getBytes(StandardCharsets.US_ASCII));
            out.write("fmt ".getBytes(StandardCharsets.US_ASCII));
            writeLE32(out, 16);
            writeLE16(out, 1);                 // PCM
            writeLE16(out, 1);                 // mono
            writeLE32(out, audio.sampleRate());
            writeLE32(out, audio.sampleRate() * 2); // byte rate
            writeLE16(out, 2);                 // block align
            writeLE16(out, 16);                // bits per sample
            out.write("data".getBytes(StandardCharsets.US_ASCII));
            writeLE32(out, dataLen);
            for (float s : samples) {
                float c = Math.max(-1f, Math.min(1f, s));
                writeLE16(out, (short) (c * 32767f));
            }
        } catch (IOException ignored) {
            // best effort
        }
        return out.toByteArray();
    }

    private static void writeLE32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
        o.write((v >> 16) & 0xff);
        o.write((v >> 24) & 0xff);
    }

    private static void writeLE16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
    }

    private static int readLE32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }
}
