package com.yingdao.aicommerce.knowledge;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeDocumentTextExtractorTest {
    @Test void extractsUtf8ChineseAndKeepsCsvFormulaAsLiteralText() {
        String original="尺码,胸围\nM,100\n=HYPERLINK(\"https://example.com\"),只是文字";
        var result=KnowledgeDocumentTextExtractor.extract(original.getBytes(StandardCharsets.UTF_8),"csv");
        assertEquals("EXTRACTED",result.status());assertEquals(original,result.text());
    }
    @Test void utf16BomSupportedButUndecodableAndBinaryDataAreStoredOnly() {
        byte[] payload="你好\n指南".getBytes(StandardCharsets.UTF_16LE);byte[] bytes=new byte[payload.length+2];bytes[0]=(byte)255;bytes[1]=(byte)254;System.arraycopy(payload,0,bytes,2,payload.length);
        assertEquals("你好\n指南",KnowledgeDocumentTextExtractor.extract(bytes,"txt").text());
        assertEquals("STORED_ONLY",KnowledgeDocumentTextExtractor.extract(new byte[]{(byte)255,(byte)200},"txt").status());
        assertEquals("STORED_ONLY",KnowledgeDocumentTextExtractor.extract(new byte[]{65,0,66},"txt").status());
    }
    @Test void extractionIsBoundedWithoutSplittingUnicodePairs() {
        String original="A".repeat(KnowledgeDocumentTextExtractor.MAX_TEXT-1)+"😀"+"B".repeat(1000000);
        var result=KnowledgeDocumentTextExtractor.extract(original.getBytes(StandardCharsets.UTF_8),"txt");
        assertEquals("PARTIAL",result.status());assertTrue(result.text().length()<=KnowledgeDocumentTextExtractor.MAX_TEXT);
        assertFalse(Character.isHighSurrogate(result.text().charAt(result.text().length()-1)));
    }
    @Test void documentBodyAndTableTextArePartialWithNoInventedFullDocumentClaim() throws Exception {
        var result=KnowledgeDocumentTextExtractor.extract(zip(Map.of("word/document.xml","<w:document xmlns:w='w'><w:body><w:p><w:r><w:t>真实正文</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>100 cm</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>")),"docx");
        assertEquals("PARTIAL",result.status());assertTrue(result.text().contains("真实正文"));assertTrue(result.text().contains("100 cm"));
    }
    @Test void externalEntitiesAreNeverResolved() throws Exception {
        String source="<!DOCTYPE root [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><w:document xmlns:w='w'><w:t>&secret;</w:t></w:document>";
        var result=KnowledgeDocumentTextExtractor.extract(zip(Map.of("word/document.xml",source)),"docx");
        assertEquals("STORED_ONLY",result.status());assertEquals("",result.text());
    }
    @Test void zipExpansionLimitAndTraversalRejectTextExtraction() throws Exception {
        var bomb=zip(Map.of("word/document.xml","A".repeat(KnowledgeDocumentTextExtractor.MAX_ENTRY+1)));
        assertEquals("STORED_ONLY",KnowledgeDocumentTextExtractor.extract(bomb,"docx").status());
        assertEquals("STORED_ONLY",KnowledgeDocumentTextExtractor.extract(zip(Map.of("../word/document.xml","<t>unsafe</t>")),"docx").status());
    }
    @Test void workbookExtractsSharedAndInlineStringsAndCachedValuesWithoutEvaluatingFormulas() throws Exception {
        var files=Map.of("xl/workbook.xml","<workbook xmlns:r='r'><sheets><sheet name='Sizes' r:id='rId1'/></sheets></workbook>",
                "xl/_rels/workbook.xml.rels","<Relationships><Relationship Id='rId1' Target='worksheets/sheet1.xml'/></Relationships>",
                "xl/sharedStrings.xml","<sst><si><t>胸围</t></si></sst>",
                "xl/worksheets/sheet1.xml","<worksheet><sheetData><row><c r='A1' t='s'><v>0</v></c><c r='B1' t='inlineStr'><is><t>100 cm</t></is></c><c r='C1'><f>HYPERLINK(\"https://example.com\")</f><v>12</v></c></row></sheetData></worksheet>");
        var result=KnowledgeDocumentTextExtractor.extract(zip(files),"xlsx");assertEquals("PARTIAL",result.status());
        assertTrue(result.text().contains("Sizes"));assertTrue(result.text().contains("A1: 胸围"));assertTrue(result.text().contains("B1: 100 cm"));assertTrue(result.text().contains("C1: 12"));assertFalse(result.text().contains("HYPERLINK"));
    }
    @Test void pdfLegacyOfficeAndMediaAreHonestStorageOnly() {
        for(String extension:List.of("pdf","doc","xls","ppt","pptx","png","jpeg","webp","mp4")) {
            var result=KnowledgeDocumentTextExtractor.extract(new byte[]{1,2,3},extension);
            assertEquals("STORED_ONLY",result.status());assertEquals("",result.text());
            if(extension.startsWith("ppt"))assertTrue(result.note().contains("尚未接入 PowerPoint"));
        }
    }
    private byte[] zip(Map<String,String> files) throws IOException {
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){for(var file:files.entrySet()){zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}return bytes.toByteArray();
    }
}
