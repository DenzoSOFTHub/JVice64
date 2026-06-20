package it.denzosoft.jvice64.ui;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.RenderedImage;
import java.io.IOException;

/**
 * Minimal animated-GIF writer: a fixed inter-frame delay and an infinite loop.
 * Used to record the C64 screen (16 colours, so GIF is a good fit) to a single
 * playable file with no external dependencies.
 */
final class GifSequenceWriter {

    private final ImageWriter writer;
    private final ImageWriteParam params;
    private final IIOMetadata metadata;

    GifSequenceWriter(ImageOutputStream out, int imageType, int delayMs, boolean loop) throws IOException {
        writer = ImageIO.getImageWritersBySuffix("gif").next();
        params = writer.getDefaultWriteParam();
        ImageTypeSpecifier its = ImageTypeSpecifier.createFromBufferedImageType(imageType);
        metadata = writer.getDefaultImageMetadata(its, params);

        String fmt = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(fmt);

        IIOMetadataNode gce = child(root, "GraphicControlExtension");
        gce.setAttribute("disposalMethod", "none");
        gce.setAttribute("userInputFlag", "FALSE");
        gce.setAttribute("transparentColorFlag", "FALSE");
        gce.setAttribute("delayTime", Integer.toString(Math.max(1, delayMs / 10)));   // hundredths of a second
        gce.setAttribute("transparentColorIndex", "0");

        if (loop) {
            IIOMetadataNode appExts = child(root, "ApplicationExtensions");
            IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
            app.setAttribute("applicationID", "NETSCAPE");
            app.setAttribute("authenticationCode", "2.0");
            app.setUserObject(new byte[] { 0x1, 0, 0 });   // loop count 0 = forever
            appExts.appendChild(app);
        }
        metadata.setFromTree(fmt, root);

        writer.setOutput(out);
        writer.prepareWriteSequence(null);
    }

    void writeFrame(RenderedImage img) throws IOException {
        writer.writeToSequence(new IIOImage(img, null, metadata), params);
    }

    void close() throws IOException {
        writer.endWriteSequence();
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode n = new IIOMetadataNode(name);
        root.appendChild(n);
        return n;
    }
}
