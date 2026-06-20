package it.denzosoft.jvice64.ui;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Self-contained AVI writer producing MJPEG video + 16-bit signed LE PCM mono
 * audio. Playable in VLC / ffmpeg.
 *
 * <p>Usage: construct with geometry, push frames via {@link #addFrame} (which
 * JPEG-encodes immediately) and audio via {@link #addAudio}, then dump the whole
 * container with {@link #writeTo}.</p>
 */
public final class AviWriter {

    private final int width;
    private final int height;
    private final double fps;
    private final int sampleRate;

    /** One JPEG-encoded byte[] per video frame. */
    private final List<byte[]> frames = new ArrayList<byte[]>();

    /** Growing mono PCM buffer (16-bit signed samples). */
    private short[] audio = new short[1 << 16];
    private int audioCount = 0;

    public AviWriter(int width, int height, double fps, int sampleRate) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.sampleRate = sampleRate;
    }

    /** JPEG-encode the image now and buffer the bytes. */
    public void addFrame(BufferedImage img) throws IOException {
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(src, "jpg", baos)) {
            throw new IOException("No JPEG ImageIO writer available");
        }
        frames.add(baos.toByteArray());
    }

    /** Append {@code count} mono PCM samples. */
    public void addAudio(short[] samples, int count) {
        if (count <= 0) {
            return;
        }
        ensureAudioCapacity(audioCount + count);
        System.arraycopy(samples, 0, audio, audioCount, count);
        audioCount += count;
    }

    private void ensureAudioCapacity(int needed) {
        if (needed <= audio.length) {
            return;
        }
        int newLen = audio.length;
        while (newLen < needed) {
            newLen <<= 1;
        }
        short[] grown = new short[newLen];
        System.arraycopy(audio, 0, grown, 0, audioCount);
        audio = grown;
    }

    /** Number of video frames buffered so far. */
    public int frameCount() {
        return frames.size();
    }

    /** Assemble the full RIFF/AVI container and write it to {@code f}. */
    public void writeTo(File f) throws IOException {
        final int nFrames = frames.size();
        final int totalAudioSamples = audioCount;

        // Audio samples per frame (mono). Last frame absorbs the remainder.
        final int samplesPerFrame = (int) Math.round(sampleRate / fps);

        // ---- Build the 'movi' body, recording an index entry per chunk. ----
        ByteArrayOutputStream moviBody = new ByteArrayOutputStream();
        List<IndexEntry> index = new ArrayList<IndexEntry>();

        int audioPos = 0;
        for (int i = 0; i < nFrames; i++) {
            // --- video chunk '00dc' ---
            byte[] jpeg = frames.get(i);
            int vidOffset = moviBody.size(); // offset of the chunk's ckid within movi body
            writeFourcc(moviBody, "00dc");
            writeIntLE(moviBody, jpeg.length);
            moviBody.write(jpeg, 0, jpeg.length);
            if ((jpeg.length & 1) != 0) {
                moviBody.write(0);
            }
            index.add(new IndexEntry("00dc", 0x10 /* AVIIF_KEYFRAME */, vidOffset, jpeg.length));

            // --- audio chunk '01wb' ---
            int sliceSamples;
            if (i == nFrames - 1) {
                sliceSamples = totalAudioSamples - audioPos;
            } else {
                sliceSamples = Math.min(samplesPerFrame, totalAudioSamples - audioPos);
            }
            if (sliceSamples < 0) {
                sliceSamples = 0;
            }
            int sliceBytes = sliceSamples * 2;
            int audOffset = moviBody.size();
            writeFourcc(moviBody, "01wb");
            writeIntLE(moviBody, sliceBytes);
            for (int s = 0; s < sliceSamples; s++) {
                short v = audio[audioPos + s];
                moviBody.write(v & 0xFF);
                moviBody.write((v >> 8) & 0xFF);
            }
            audioPos += sliceSamples;
            if ((sliceBytes & 1) != 0) {
                moviBody.write(0);
            }
            index.add(new IndexEntry("01wb", 0, audOffset, sliceBytes));
        }

        byte[] movi = moviBody.toByteArray();

        // ---- Header sizing ----
        // Largest video frame, for dwSuggestedBufferSize / dwMaxBytesPerSec.
        int maxFrame = 0;
        for (byte[] jpeg : frames) {
            if (jpeg.length > maxFrame) {
                maxFrame = jpeg.length;
            }
        }
        int audioBytesPerSec = sampleRate * 2;
        int videoBytesPerSec = (int) Math.round(maxFrame * fps);
        int maxBytesPerSec = videoBytesPerSec + audioBytesPerSec;

        // ---- 'avih' MainAVIHeader (56 bytes) ----
        ByteArrayOutputStream avih = new ByteArrayOutputStream();
        writeIntLE(avih, (int) Math.round(1e6 / fps)); // dwMicroSecPerFrame
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
        writeIntLE(vstrh, 1);                          // dwScale
        writeIntLE(vstrh, (int) Math.round(fps));      // dwRate
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
        writeIntLE(astrh, totalAudioSamples);          // dwLength (in samples)
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

        // ---- Assemble LIST 'strl' (video) ----
        ByteArrayOutputStream vstrl = new ByteArrayOutputStream();
        writeFourcc(vstrl, "strl");
        writeChunk(vstrl, "strh", vstrh.toByteArray());
        writeChunk(vstrl, "strf", vstrf.toByteArray());

        // ---- Assemble LIST 'strl' (audio) ----
        ByteArrayOutputStream astrl = new ByteArrayOutputStream();
        writeFourcc(astrl, "strl");
        writeChunk(astrl, "strh", astrh.toByteArray());
        writeChunk(astrl, "strf", astrf.toByteArray());

        // ---- Assemble LIST 'hdrl' ----
        ByteArrayOutputStream hdrl = new ByteArrayOutputStream();
        writeFourcc(hdrl, "hdrl");
        writeChunk(hdrl, "avih", avih.toByteArray());
        writeList(hdrl, vstrl.toByteArray());
        writeList(hdrl, astrl.toByteArray());

        // ---- 'idx1' index ----
        ByteArrayOutputStream idx1 = new ByteArrayOutputStream();
        for (IndexEntry e : index) {
            writeFourcc(idx1, e.ckid);
            writeIntLE(idx1, e.flags);
            // dwChunkOffset is conventionally relative to the 'movi' FOURCC (Microsoft/ffmpeg
            // convention), so the first chunk lands at 4. e.offset is movi-body-relative, hence +4.
            writeIntLE(idx1, e.offset + 4);
            writeIntLE(idx1, e.size);
        }

        // ---- Top-level RIFF assembly ----
        // RIFF 'AVI ' { LIST hdrl } { LIST movi } { idx1 }
        ByteArrayOutputStream riffBody = new ByteArrayOutputStream();
        writeFourcc(riffBody, "AVI ");
        writeList(riffBody, hdrl.toByteArray());

        // LIST 'movi'
        writeFourcc(riffBody, "LIST");
        writeIntLE(riffBody, 4 + movi.length); // 'movi' FOURCC + body
        writeFourcc(riffBody, "movi");
        riffBody.write(movi, 0, movi.length);

        // idx1 chunk
        writeChunk(riffBody, "idx1", idx1.toByteArray());

        byte[] body = riffBody.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFourcc(out, "RIFF");
        writeIntLE(out, body.length);
        out.write(body, 0, body.length);

        Files.write(f.toPath(), out.toByteArray());
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    /** Index entry: ckid, flags, offset (from movi data start), unpadded size. */
    private static final class IndexEntry {
        final String ckid;
        final int flags;
        final int offset;
        final int size;

        IndexEntry(String ckid, int flags, int offset, int size) {
            this.ckid = ckid;
            this.flags = flags;
            this.offset = offset;
            this.size = size;
        }
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
