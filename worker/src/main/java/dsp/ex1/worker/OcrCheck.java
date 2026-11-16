package dsp.ex1.worker;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Local sanity check of download + OCR, with no AWS involved.
 * Usage: java -cp worker.jar dsp.ex1.worker.OcrCheck <imageUrl-or-localPath> [...]
 */
public class OcrCheck {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: java -cp worker.jar dsp.ex1.worker.OcrCheck <imageUrl-or-localPath> [...]");
            System.exit(1);
        }
        ImageDownloader downloader = new ImageDownloader();
        OcrService ocr = new OcrService();
        for (String source : args) {
            System.out.println("=== " + source);
            try {
                System.out.println(ocr.extractText(load(source, downloader)));
            } catch (Exception | Error e) {
                System.out.println("ERROR " + Worker.describe(e));
            }
        }
    }

    private static BufferedImage load(String source, ImageDownloader downloader)
            throws IOException, InterruptedException {
        File file = new File(source);
        if (!file.isFile()) {
            return downloader.download(source);
        }
        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IOException("Not a supported image format (use PNG, JPG, GIF or BMP)");
        }
        return image;
    }
}
