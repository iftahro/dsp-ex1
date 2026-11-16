package dsp.ex1.worker;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import java.awt.image.BufferedImage;

/**
 * Thin wrapper around Tesseract. Requires the native tesseract-ocr package and its language data.
 * Not thread-safe: use one instance per thread.
 */
public class OcrService {

    /** Default tessdata location on Ubuntu/Debian; override with env var TESSDATA_PREFIX. */
    private static final String DEFAULT_TESSDATA = "/usr/share/tesseract-ocr/5/tessdata";

    private final Tesseract tesseract = new Tesseract();

    public OcrService() {
        String dataPath = System.getenv().getOrDefault("TESSDATA_PREFIX", DEFAULT_TESSDATA);
        tesseract.setDatapath(dataPath);
        tesseract.setLanguage("eng");
    }

    public String extractText(BufferedImage image) throws TesseractException {
        return tesseract.doOCR(image).trim();
    }
}
