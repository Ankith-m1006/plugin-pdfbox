package io.kestra.plugin.pdfbox;

import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.StorageInterface;
import io.kestra.core.tenant.TenantService;
import io.kestra.core.utils.IdUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDXFAResource;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class Fixtures {
    private Fixtures() {
    }

    public static byte[] textPdf(String... pages) throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (String text : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 720);
                    content.showText(text);
                    content.endText();
                }
            }
            return bytes(document);
        }
    }

    public static byte[] imagePdf(int images) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                for (int i = 0; i < images; i++) {
                    PDImageXObject image = LosslessFactory.createFromImage(document, image(40 + i, 30, Color.RED));
                    content.drawImage(image, 72 + i * 60, 600);
                }
            }
            return bytes(document);
        }
    }

    public static byte[] formPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDAcroForm form = new PDAcroForm(document);
            document.getDocumentCatalog().setAcroForm(form);
            PDResources resources = new PDResources();
            resources.put(COSName.HELV, new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setDefaultResources(resources);
            form.setDefaultAppearance("/Helv 0 Tf 0 g");

            PDTextField name = new PDTextField(form);
            name.setPartialName("full_name");
            widget(name.getWidgets().get(0), page, new PDRectangle(72, 700, 200, 20));
            form.getFields().add(name);

            PDCheckBox remote = new PDCheckBox(form);
            remote.setPartialName("remote");
            widget(remote.getWidgets().get(0), page, new PDRectangle(72, 650, 15, 15));
            // A check box needs an appearance per state; "Yes" becomes its on value.
            COSDictionary states = new COSDictionary();
            states.setItem(COSName.getPDFName("Yes"),
                new PDAppearanceStream(document).getCOSObject());
            states.setItem(COSName.Off,
                new PDAppearanceStream(document).getCOSObject());
            COSDictionary appearance = new COSDictionary();
            appearance.setItem(COSName.N, states);
            remote.getWidgets().get(0).setAppearance(
                new PDAppearanceDictionary(appearance));
            form.getFields().add(remote);

            return bytes(document);
        }
    }

    public static byte[] emptyPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            return bytes(document);
        }
    }

    /** One blank page of the given size in points; a big one stands in for a hostile media box. */
    public static byte[] pagePdf(float width, float height) throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(width, height)));
            return bytes(document);
        }
    }

    /** The form of {@link #formPdf()} with an XFA packet, like the hybrid forms Acrobat produces. */
    public static byte[] hybridFormPdf() throws Exception {
        try (PDDocument document = Loader.loadPDF(formPdf())) {
            COSStream xfa = document.getDocument().createCOSStream();
            try (var out = xfa.createOutputStream()) {
                out.write("<xdp:xdp xmlns:xdp=\"http://ns.adobe.com/xdp/\"/>".getBytes(StandardCharsets.UTF_8));
            }
            document.getDocumentCatalog().getAcroForm().setXFA(new PDXFAResource(xfa));
            return bytes(document);
        }
    }

    public static byte[] encrypt(byte[] pdf, String userPassword, String ownerPassword) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            AccessPermission permission = new AccessPermission();
            permission.setCanModify(false);
            StandardProtectionPolicy policy = new StandardProtectionPolicy(ownerPassword, userPassword, permission);
            policy.setEncryptionKeyLength(256);
            document.protect(policy);
            return bytes(document);
        }
    }

    public static byte[] corrupt() {
        return "%PDF-1.7\nthis is not really a pdf".getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image(width, height, Color.BLUE), "png", out);
        return out.toByteArray();
    }

    public static URI put(StorageInterface storage, byte[] content, String extension) throws Exception {
        return storage.put(TenantService.MAIN_TENANT, null, new URI("/" + IdUtils.create() + extension), new ByteArrayInputStream(content));
    }

    public static PDDocument read(RunContext runContext, URI uri) throws Exception {
        try (InputStream in = runContext.storage().getFile(uri)) {
            return Loader.loadPDF(in.readAllBytes());
        }
    }

    public static PDDocument read(RunContext runContext, URI uri, String password) throws Exception {
        try (InputStream in = runContext.storage().getFile(uri)) {
            return Loader.loadPDF(in.readAllBytes(), password);
        }
    }

    private static void widget(PDAnnotationWidget widget, PDPage page, PDRectangle rectangle) throws Exception {
        widget.setRectangle(rectangle);
        widget.setPage(page);
        page.getAnnotations().add(widget);
    }

    private static BufferedImage image(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    private static byte[] bytes(PDDocument document) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }
}
