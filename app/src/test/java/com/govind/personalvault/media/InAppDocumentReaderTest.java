package com.govind.personalvault.media;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InAppDocumentReaderTest {
    @Test
    public void stripXmlKeepsParagraphText() {
        String xml = "<w:p><w:r><w:t>Hello</w:t></w:r></w:p><w:p><w:r><w:t>Vault</w:t></w:r></w:p>";
        String text = InAppDocumentReader.stripXml(xml, 1000);
        assertTrue(text.contains("Hello"));
        assertTrue(text.contains("Vault"));
    }

    @Test
    public void stripXmlDecodesEntities() {
        String xml = "<w:t>A \u0026amp; B \u0026lt; C</w:t>";
        assertEquals("A & B < C", InAppDocumentReader.stripXml(xml, 1000));
    }
}
