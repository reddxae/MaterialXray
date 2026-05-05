package com.material.xray.data.parser

internal fun parseQuery(query: String): Map<String, String> =
    query.split("&")
        .filter { it.contains("=") }
        .associate {
            val (key, value) = it.split("=", limit = 2)
            key to value
        }
