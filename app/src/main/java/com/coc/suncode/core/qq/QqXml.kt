package com.coc.suncode.core.qq

import org.w3c.dom.Document
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Parse companion preferences and UI hierarchies with Android's DOM provider. */
internal object QqXml {
    fun parse(source: String): Document {
        // Android does not support Xerces feature URIs; reject DTDs before parsing instead.
        require(!source.contains("<!DOCTYPE", ignoreCase = true)) { "QQ XML 包含不支持的 DTD" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External entities are forbidden") }
        }
        return builder.parse(InputSource(StringReader(source)))
    }
}
