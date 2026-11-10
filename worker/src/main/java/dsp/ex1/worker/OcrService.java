package dsp.ex1.worker;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import java.io.File;

/** Thin wrapper around Tesseract. Requires the native tesseract-ocr package and its language data. */
public class OcrService {

    /** Override with env var TESSDATA_PREFIX if tessdata lives elsewhere on the machine. */
    private static final String DEFAULT_TESSDATA = "/usr/share/tesseract-ocr/5/tessdata";

    private final Tesseract tesseract = new Tesseract();

    public OcrService() {
        String dataPath = System.getenv().getOrDefault("TESSDATA_PREFIX", DEFAULT_TESSDATA);
        tesseract.setDatapath(dataPath);
        tesseract.setLanguage("eng");
    }

    public String extractText(File image) throws TesseractException {
        return tesseract.doOCR(image).trim();
    }
}
