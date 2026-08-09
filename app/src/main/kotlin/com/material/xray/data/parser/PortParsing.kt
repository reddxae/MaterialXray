package com.material.xray.data.parser

private val VALID_PORT_RANGE = 1..65_535

/**
 * Returns this value when it is a valid TCP/UDP port, or null otherwise.
 *
 * `java.net.URI` reports an absent port as -1 and accepts arbitrarily large numbers, so both ends
 * of the range have to be checked before a port is persisted into a server configuration.
 */
internal fun Int.takeIfValidPort(): Int? = takeIf { it in VALID_PORT_RANGE }

internal fun String.toValidPortOrNull(): Int? = toIntOrNull()?.takeIfValidPort()
