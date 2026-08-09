package com.material.xray.data.parser

import java.net.URLDecoder

/**
 * Decodes a percent-encoded URI component per RFC 3986.
 *
 * Share links are plain URIs, not `application/x-www-form-urlencoded` submissions, so a literal
 * `+` must stay a plus sign instead of becoming the space that [URLDecoder] alone would produce.
 *
 * Throws [IllegalArgumentException] on malformed percent escapes; use [decodeUriComponentLeniently]
 * where a malformed component should degrade to its raw form instead of failing the caller.
 */
internal fun decodeUriComponent(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

/**
 * Like [decodeUriComponent], but a component with malformed percent escapes is returned verbatim.
 *
 * Share links found in the wild routinely carry stray `%` characters in cosmetic fields such as
 * names; rejecting the whole link over them would lose otherwise usable configurations.
 */
internal fun decodeUriComponentLeniently(value: String): String = runCatching { decodeUriComponent(value) }.getOrDefault(value)
