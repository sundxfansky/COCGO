package com.coc.suncode.core.qq

import org.w3c.dom.Element
import java.io.StringWriter
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Edit only the named string while preserving unrelated companion preferences. */
internal object QqToolPreferences {
    fun putString(xml: String?, key: String, value: String): String {
        val document = QqXml.parse(xml ?: "<map/>")
        val root = document.documentElement
        require(root.tagName == "map") { "授权工具偏好设置格式错误" }
        val matches = (0 until root.childNodes.length).map { root.childNodes.item(it) }
            .filterIsInstance<Element>().filter { it.getAttribute("name") == key }
        matches.forEach { root.removeChild(it) }
        root.appendChild(document.createElement("string").apply {
            setAttribute("name", key)
            textContent = value
        })
        val output = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }.transform(DOMSource(document), StreamResult(output))
        return output.toString()
    }
}
