package net.speakerapi.internal.bzip2;

import java.io.IOException;
import java.io.InputStream;

/**
 * Drop-in bzip2 decompression stream that reads the standard {@code "BZ"} magic
 * itself, then delegates to {@link CBZip2InputStream} (which, per its contract,
 * expects the magic to have been consumed already).
 *
 * <p>Vendored from Apache Ant's {@code org.apache.tools.bzip2} package
 * (Apache-2.0; based on Keiron Liddle's bzip2 implementation). Pure Java, zero
 * third-party dependencies.</p>
 */
public class BZip2InputStream extends InputStream {

    private final CBZip2InputStream delegate;

    public BZip2InputStream(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 != 'B' || b1 != 'Z') {
            throw new IOException("Not a BZip2 stream: missing 'BZ' magic header");
        }
        this.delegate = new CBZip2InputStream(in);
    }

    @Override
    public int read() throws IOException {
        return delegate.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return delegate.read(b, off, len);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
