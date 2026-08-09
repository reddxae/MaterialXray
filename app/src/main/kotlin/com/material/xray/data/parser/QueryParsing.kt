package com.material.xray.data.parser

/**
 * Splits a raw query string and percent-decodes each key and value exactly once.
 *
 * Decoding happens here so individual parsers cannot forget to decode a field. The input must be
 * the raw (still encoded) query; passing a pre-decoded string would double-decode. Components with
 * malformed percent escapes are kept verbatim rather than failing the whole link.
 */
internal fun parseQuery(query: String): Map<String, String> = query.split("&")
    .filter { it.contains("=") }
    .associate {
        val (key, value) = it.split("=", limit = 2)
        decodeUriComponentLeniently(key) to decodeUriComponentLeniently(value)
    }
