package org.mariadb.jdbc.internal.stream;

import org.mariadb.jdbc.internal.util.buffer.ReadUtil;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidParameterException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public class DecompressInputStream extends InputStream {
    InputStream baseStream;
    int remainingBytes;
    byte[] header;
    boolean doDecompress;
    ByteArrayInputStream decompressedByteStream;

    public DecompressInputStream(InputStream baseStream) {
        this.baseStream = baseStream;
        header = new byte[7];
    }

    @Override
    public int read(byte[] bytes, int off, int len) throws IOException {
        if (len == 0 || off < 0 || bytes == null) {
            throw new InvalidParameterException();
        }

        if (remainingBytes == 0) {
            nextPacket();
        }

        int ret;
        int bytesToRead = Math.min(remainingBytes, len);
        if (doDecompress) {
            ret = decompressedByteStream.read(bytes, off, bytesToRead);
        } else {
            ret = baseStream.read(bytes, off, bytesToRead);
        }
        if (ret <= 0) {
            throw new EOFException("got " + ret + " bytes, bytesToRead = " + bytesToRead);
        }

        remainingBytes -= ret;
        return ret;
    }

    @Override
    public int read(byte[] bytes) throws IOException {
        return read(bytes, 0, bytes.length);
    }

    @Override
    public int read() throws IOException {
        byte[] buffer = new byte[1];
        if (read(buffer) == 0) {
            return -1;
        }
        return (buffer[0] & 0xff);
    }


    /**
     * Read stream header. If required, decompress compressed stream.
     *
     * @throws IOException exception
     */
    private void nextPacket() throws IOException {
        ReadUtil.readFully(baseStream, header);
        int compressedLength = (header[0] & 0xff) + ((header[1] & 0xff) << 8) + ((header[2] & 0xff) << 16);
        int decompressedLength = (header[4] & 0xff) + ((header[5] & 0xff) << 8) + ((header[6] & 0xff) << 16);
        if (decompressedLength != 0) {
            doDecompress = true;
            remainingBytes += decompressedLength;
            byte[] compressedBuffer = new byte[compressedLength];
            byte[] decompressedBuffer = new byte[decompressedLength];
            ReadUtil.readFully(baseStream, compressedBuffer);
            Inflater inflater = new Inflater();
            inflater.setInput(compressedBuffer);
            try {
                int actualUncompressBytes = inflater.inflate(decompressedBuffer);
                if (actualUncompressBytes != decompressedLength) {
                    throw new IOException("Invalid stream length after decompression " + actualUncompressBytes + ",expected "
                            + decompressedLength);
                }
            } catch (DataFormatException dfe) {
                throw new IOException(dfe);
            }
            inflater.end();
            decompressedByteStream = new ByteArrayInputStream(decompressedBuffer);

        } else {
            doDecompress = false;
            remainingBytes += compressedLength;
            decompressedByteStream = null;
        }
    }

}
