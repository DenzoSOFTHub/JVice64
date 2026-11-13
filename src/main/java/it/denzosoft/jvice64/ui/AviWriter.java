package it.denzosoft.jvice64.ui;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

import javax.imageio.ImageIO;

/**
 * Self-contained AVI writer producing MJPEG video + 16-bit signed LE PCM mono
 * audio. Playable in VLC / ffmpeg.
 *
 * <p>The file is STREAMED to disk while recording: the constructor writes the
 * headers with placeholder sizes, {@link #addFrame} / {@link #addAudio} append
 * one {@code 00dc} / {@code 01wb} chunk each (so audio and video interleave in
 * arrival order), and {@link #close} appends the {@code idx1} index and patches
 * the sizes and counts. Only the index (16 bytes per chunk) stays in memory, so
 * a long recording no longer runs out of heap.</p>
 *
 * <p>AVI 1.0 files are limited to 2 GB and many players stop there, so the
 * writer reports {@link #isFull()} at about 1.9 GB; the caller then closes it.</p>
 */
public final class AviWriter {

    /** Stop well before the 2 GB RIFF limit of AVI 1.0. */
    private static final long MAX_BYTES = 1_900_000_000L;

    private static final int AVIIF_KEYFRAME = 0x10;
    private static final int ID_VIDEO = 0, ID_AUDIO = 1;

    private final int width;
    private final int height;
    private final int fpsNum, fpsDen;    // video frame rate = fpsNum / fpsDen, exactly
    private final double fps;
    private final int sampleRate;

    private final RandomAccessFile raf;
    private final OutputStream out;      // buffered, shares raf's file pointer
    private final int headerLen;         // bytes from 'RIFF' up to and including the 'movi' FOURCC
    private long pos;                    // bytes written so far (= current file offset)

    private int frames;
    private long audioSamples;
    private int maxFrame;
    private boolean closed;

    /** Index: per chunk {stream id, flags, offset from the 'movi' FOURCC, size}. */
    private int[] index = new int[4 * 1024];
    private int indexCount;

    private byte[] pcm = new byte[4096];

    /**
     * Create {@code f} and write the headers; frames and audio are appended as they arrive.
     * The video rate is the exact fraction {@code fpsNum / fpsDen} (stored as the stream's
     * dwRate / dwScale), so a non-integer emulated rate such as PAL's 25.062 fps keeps
     * the picture in sync with the audio.
     */
    public AviWriter(File f, int width, int height, int fpsNum, int fpsDen, int sampleRate) throws IOException {
        if (fpsNum <= 0 || fpsDen <= 0) throw new IllegalArgumentException("bad frame rate " + fpsNum + "/" + fpsDen);
        this.width = width;
        this.height = height;
        this.fpsNum = fpsNum;
        this.fpsDen = fpsDen;
        this.fps = fpsNum / (double) fpsDen;
        this.sampleRate = sampleRate;
        this.raf = new RandomAccessFile(f, "rw");
        try {
            raf.setLength(0);
            this.out = new BufferedOutputStream(new FileOutputStream(raf.getFD()), 1 << 16);
            byte[] hdr = header(0, 0, 0, 0);
            headerLen = hdr.length;
            out.write(hdr);
            pos = hdr.length;
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e;
        }
    }

    /** JPEG-encode the image and append it as one video chunk. */
    public void addFrame(BufferedImage img) throws IOException {
        if (closed) return;
        BufferedImage src = img;
        // ImageIO's JPEG writer cannot handle images with an alpha channel
        // (TYPE_INT_ARGB, etc.); copy into a plain RGB image if needed.
        if (img.getType() != BufferedImage.TYPE_INT_RGB) {
            BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Graphics g = rgb.getGraphics();
            try {
                g.drawImage(img, 0, 0, null);
            } finally {
                g.dispose();
            }
            src = rgb;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream(32 * 1024);
        if (!ImageIO.write(src, "jpg", baos)) {
            throw new IOException("No JPEG ImageIO writer available");
        }
        byte[] jpeg = baos.toByteArray();
        writeChunk(ID_VIDEO, AVIIF_KEYFRAME, jpeg, jpeg.length);
        frames++;
        if (jpeg.length > maxFrame) maxFrame = jpeg.length;
    }

    /** Append {@code count} mono PCM samples as one audio chunk. */
    public void addAudio(short[] samples, int count) throws IOException {
        if (closed || count <= 0) return;
        int bytes = count * 2;
        if (pcm.length < bytes) pcm = new byte[bytes];
        for (int i = 0; i < count; i++) {
            short v = samples[i];
            pcm[2 * i] = (byte) v;
            pcm[2 * i + 1] = (byte) (v >> 8);
        }
        writeChunk(ID_AUDIO, 0, pcm, bytes);
        audioSamples += count;
    }

    /** Number of video frames written so far. */
    public int frameCount() {
        return frames;
    }

    /** True once the file is close to the AVI 1.0 size limit; the caller should {@link #close} it. */
    public boolean isFull() {
        return pos >= MAX_BYTES;
    }

    /** Append the index, patch the header sizes/counts and close the file. */
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            long moviEnd = pos;
            // idx1: offsets are relative to the 'movi' FOURCC (Microsoft/ffmpeg
            // convention), so the first chunk lands at 4.
            int idxBytes = indexCount * 16;
            byte[] idx = new byte[8 + idxBytes];
            putFourcc(idx, 0, "idx1");
            putInt(idx, 4, idxBytes);
            for (int i = 0; i < indexCount; i++) {
                int b = 8 + i * 16;
                putFourcc(idx, b, index[4 * i] == ID_VIDEO ? "00dc" : "01wb");
                putInt(idx, b + 4, index[4 * i + 1]);
                putInt(idx, b + 8, index[4 * i + 2]);
                putInt(idx, b + 12, index[4 * i + 3]);
            }
            out.write(idx);
            pos += idx.length;
            out.flush();

            long riffSize = pos - 8;
            long moviSize = moviEnd - (headerLen - 4);   // 'movi' FOURCC + all chunks
            raf.seek(0);
            raf.write(header(frames, audioSamples, riffSize, moviSize));
        } finally {
            try { out.close(); } finally { raf.close(); }
        }
    }

    // ----------------------------------------------------------------------
    // Chunks
    // ----------------------------------------------------------------------

    private void writeChunk(int id, int flags, byte[] data, int len) throws IOException {
        long moviFourcc = headerLen - 4;
        addIndex(id, flags, (int) (pos - moviFourcc), len);
        byte[] h = new byte[8];
        putFourcc(h, 0, id == ID_VIDEO ? "00dc" : "01wb");
        putInt(h, 4, len);
        out.write(h);
        out.write(data, 0, len);
        pos += 8 + len;
        if ((len & 1) != 0) {
            out.write(0);
            pos++;
        }
    }

    private void addIndex(int id, int flags, int offset, int size) {
        if (4 * (indexCount + 1) > index.length) {
            int[] grown = new int[index.length * 2];
            System.arraycopy(index, 0, grown, 0, 4 * indexCount);
            index = grown;
        }
        int b = 4 * indexCount++;
        index[b] = id;
        index[b + 1] = flags;
        index[b + 2] = offset;
        index[b + 3] = size;
    }

    // ----------------------------------------------------------------------
    // Header: RIFF 'AVI ' { LIST hdrl } LIST <size> 'movi'
    // Its length does not depend on the values, so it can be rewritten in place.
    // ----------------------------------------------------------------------

    private byte[] header(int nFrames, long totalAudioSamples, long riffSize, long moviSize) {
        // Frame rate as a rational (dwRate / dwScale) so a fractional fps is exact.
        final int scale = fpsDen;
        final int rate = fpsNum;
        int audioBytesPerSec = sampleRate * 2;
        int videoBytesPerSec = (int) Math.round(maxFrame * fps);
        int maxBytesPerSec = videoBytesPerSec + audioBytesPerSec;

        // ---- 'avih' MainAVIHeader (56 bytes) ----
        ByteArrayOutputStream avih = new ByteArrayOutputStream();
        writeIntLE(avih, (int) Math.round(1e6 * fpsDen / fpsNum)); // dwMicroSecPerFrame
        writeIntLE(avih, maxBytesPerSec);              // dwMaxBytesPerSec
        writeIntLE(avih, 0);                           // dwPaddingGranularity
        writeIntLE(avih, 0x10);                        // dwFlags = AVIF_HASINDEX
        writeIntLE(avih, nFrames);                     // dwTotalFrames
        writeIntLE(avih, 0);                           // dwInitialFrames
        writeIntLE(avih, 2);                           // dwStreams (video + audio)
        writeIntLE(avih, maxFrame);                    // dwSuggestedBufferSize
        writeIntLE(avih, width);                       // dwWidth
        writeIntLE(avih, height);                      // dwHeight
        writeIntLE(avih, 0);                           // dwReserved[0]
        writeIntLE(avih, 0);                           // dwReserved[1]
        writeIntLE(avih, 0);                           // dwReserved[2]
        writeIntLE(avih, 0);                           // dwReserved[3]

        // ---- Video stream: 'strh' (56 bytes) ----
        ByteArrayOutputStream vstrh = new ByteArrayOutputStream();
        writeFourcc(vstrh, "vids");                    // fccType
        writeFourcc(vstrh, "MJPG");                    // fccHandler
        writeIntLE(vstrh, 0);                          // dwFlags
        writeShortLE(vstrh, 0);                        // wPriority
        writeShortLE(vstrh, 0);                        // wLanguage
        writeIntLE(vstrh, 0);                          // dwInitialFrames
        writeIntLE(vstrh, scale);                      // dwScale
        writeIntLE(vstrh, rate);                       // dwRate  (fps = rate / scale)
        writeIntLE(vstrh, 0);                          // dwStart
        writeIntLE(vstrh, nFrames);                    // dwLength
        writeIntLE(vstrh, maxFrame);                   // dwSuggestedBufferSize
        writeIntLE(vstrh, 0);                          // dwQuality (-1 default; 0 fine)
        writeIntLE(vstrh, 0);                          // dwSampleSize (variable for video)
        // rcFrame: short left, top, right, bottom
        writeShortLE(vstrh, 0);
        writeShortLE(vstrh, 0);
        writeShortLE(vstrh, width);
        writeShortLE(vstrh, height);

        // ---- Video stream: 'strf' BITMAPINFOHEADER (40 bytes) ----
        ByteArrayOutputStream vstrf = new ByteArrayOutputStream();
        writeIntLE(vstrf, 40);                         // biSize
        writeIntLE(vstrf, width);                      // biWidth
        writeIntLE(vstrf, height);                     // biHeight
        writeShortLE(vstrf, 1);                        // biPlanes
        writeShortLE(vstrf, 24);                       // biBitCount
        writeFourcc(vstrf, "MJPG");                    // biCompression
        writeIntLE(vstrf, width * height * 3);         // biSizeImage
        writeIntLE(vstrf, 0);                          // biXPelsPerMeter
        writeIntLE(vstrf, 0);                          // biYPelsPerMeter
        writeIntLE(vstrf, 0);                          // biClrUsed
        writeIntLE(vstrf, 0);                          // biClrImportant

        // ---- Audio stream: 'strh' (56 bytes) ----
        ByteArrayOutputStream astrh = new ByteArrayOutputStream();
        writeFourcc(astrh, "auds");                    // fccType
        writeIntLE(astrh, 0);                          // fccHandler (0 for PCM)
        writeIntLE(astrh, 0);                          // dwFlags
        writeShortLE(astrh, 0);                        // wPriority
        writeShortLE(astrh, 0);                        // wLanguage
        writeIntLE(astrh, 0);                          // dwInitialFrames
        writeIntLE(astrh, 1);                          // dwScale
        writeIntLE(astrh, sampleRate);                 // dwRate
        writeIntLE(astrh, 0);                          // dwStart
        writeIntLE(astrh, (int) totalAudioSamples);    // dwLength (in samples)
        writeIntLE(astrh, audioBytesPerSec);           // dwSuggestedBufferSize
        writeIntLE(astrh, 0);                          // dwQuality
        writeIntLE(astrh, 2);                          // dwSampleSize (block align)
        writeShortLE(astrh, 0);                        // rcFrame
        writeShortLE(astrh, 0);
        writeShortLE(astrh, 0);
        writeShortLE(astrh, 0);

        // ---- Audio stream: 'strf' WAVEFORMATEX (18 bytes) ----
        ByteArrayOutputStream astrf = new ByteArrayOutputStream();
        writeShortLE(astrf, 1);                        // wFormatTag = PCM
        writeShortLE(astrf, 1);                        // nChannels = mono
        writeIntLE(astrf, sampleRate);                 // nSamplesPerSec
        writeIntLE(astrf, audioBytesPerSec);           // nAvgBytesPerSec
        writeShortLE(astrf, 2);                        // nBlockAlign
        writeShortLE(astrf, 16);                       // wBitsPerSample
        writeShortLE(astrf, 0);                        // cbSize

        // ---- LIST 'strl' (video), LIST 'strl' (audio), LIST 'hdrl' ----
        ByteArrayOutputStream vstrl = new ByteArrayOutputStream();
        writeFourcc(vstrl, "strl");
        writeChunk(vstrl, "strh", vstrh.toByteArray());
        writeChunk(vstrl, "strf", vstrf.toByteArray());

        ByteArrayOutputStream astrl = new ByteArrayOutputStream();
        writeFourcc(astrl, "strl");
        writeChunk(astrl, "strh", astrh.toByteArray());
        writeChunk(astrl, "strf", astrf.toByteArray());

        ByteArrayOutputStream hdrl = new ByteArrayOutputStream();
        writeFourcc(hdrl, "hdrl");
        writeChunk(hdrl, "avih", avih.toByteArray());
        writeList(hdrl, vstrl.toByteArray());
        writeList(hdrl, astrl.toByteArray());

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeFourcc(o, "RIFF");
        writeIntLE(o, (int) riffSize);
        writeFourcc(o, "AVI ");
        writeList(o, hdrl.toByteArray());
        writeFourcc(o, "LIST");
        writeIntLE(o, (int) moviSize);
        writeFourcc(o, "movi");
        return o.toByteArray();
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private static void putFourcc(byte[] b, int at, String s) {
        for (int i = 0; i < 4; i++) b[at + i] = (byte) s.charAt(i);
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
        b[at + 2] = (byte) (v >> 16);
        b[at + 3] = (byte) (v >> 24);
    }

    private static void writeFourcc(ByteArrayOutputStream o, String s) {
        // FOURCCs are exactly 4 ASCII bytes.
        for (int i = 0; i < 4; i++) {
            char c = i < s.length() ? s.charAt(i) : ' ';
            o.write(c & 0xFF);
        }
    }

    private static void writeIntLE(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }

    private static void writeShortLE(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    /** Write a chunk: ckid + LE size + data + even padding. */
    private static void writeChunk(ByteArrayOutputStream o, String ckid, byte[] data) {
        writeFourcc(o, ckid);
        writeIntLE(o, data.length);
        o.write(data, 0, data.length);
        if ((data.length & 1) != 0) {
            o.write(0);
        }
    }

    /**
     * Write a 'LIST' chunk whose payload already begins with its list-type
     * FOURCC (e.g. 'hdrl', 'strl').
     */
    private static void writeList(ByteArrayOutputStream o, byte[] listBody) {
        writeFourcc(o, "LIST");
        writeIntLE(o, listBody.length);
        o.write(listBody, 0, listBody.length);
        if ((listBody.length & 1) != 0) {
            o.write(0);
        }
    }
}
