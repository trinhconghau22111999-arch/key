package com.viettype.smartkey

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tự viết file .xlsx TỐI GIẢN bằng tay (không dùng thư viện Apache POI nặng nề)
 * - định dạng XLSX thực chất là 1 file ZIP chứa vài file XML theo chuẩn OOXML.
 * Chỉ cần đúng bộ khung tối thiểu (Content_Types, workbook, 1 sheet dữ liệu) là
 * Excel/Google Sheets/WPS đều mở đọc được bình thường.
 */
object XlsxExporter {

    /** [rows]: mỗi phần tử là 1 hàng, mỗi hàng là danh sách các ô (chuỗi text). */
    fun writeXlsx(outputFile: File, headerRow: List<String>, rows: List<List<String>>) {
        ZipOutputStream(outputFile.outputStream()).use { zip ->
            writeEntry(zip, "[Content_Types].xml", CONTENT_TYPES_XML)
            writeEntry(zip, "_rels/.rels", RELS_XML)
            writeEntry(zip, "xl/workbook.xml", WORKBOOK_XML)
            writeEntry(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS_XML)
            writeEntry(zip, "xl/worksheets/sheet1.xml", buildSheetXml(headerRow, rows))
        }
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun buildSheetXml(headerRow: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<sheetData>")

        appendRow(sb, 1, headerRow)
        rows.forEachIndexed { index, row -> appendRow(sb, index + 2, row) }

        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun appendRow(sb: StringBuilder, rowIndex: Int, cells: List<String>) {
        sb.append("<row r=\"").append(rowIndex).append("\">")
        cells.forEachIndexed { colIndex, cellText ->
            val cellRef = columnLetter(colIndex) + rowIndex
            sb.append("<c r=\"").append(cellRef).append("\" t=\"inlineStr\"><is><t>")
                .append(escapeXml(cellText))
                .append("</t></is></c>")
        }
        sb.append("</row>")
    }

    private fun columnLetter(zeroBasedIndex: Int): String {
        var n = zeroBasedIndex
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + (n % 26)))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private const val CONTENT_TYPES_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""

    private const val RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private const val WORKBOOK_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="LichSuQuet" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

    private const val WORKBOOK_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""
}
