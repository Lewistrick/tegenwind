package com.tegenwind.app.routes

import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Reads the track (or, failing that, route) points from a GPX file. */
object Gpx {
    fun parse(input: InputStream): List<GeoPoint> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // GPX files never need external entities; refusing them avoids XXE surprises.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().parse(input)
        for (tag in listOf("trkpt", "rtept")) {
            val nodes = doc.getElementsByTagName(tag)
            if (nodes.length > 0) {
                return (0 until nodes.length).map { i ->
                    val e = nodes.item(i) as Element
                    GeoPoint(e.getAttribute("lat").toDouble(), e.getAttribute("lon").toDouble())
                }
            }
        }
        return emptyList()
    }
}
