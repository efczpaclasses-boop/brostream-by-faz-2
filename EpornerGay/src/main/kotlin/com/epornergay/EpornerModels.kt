package com.epornergay

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpornerSearchResponse(
    @JsonProperty("total_pages") val totalPages: Int = 0,
    @JsonProperty("current_page") val currentPage: Int = 0,
    val videos: List<EpornerVideo> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpornerVideo(
    val id: String = "",
    val title: String = "",
    val url: String = "",
    val keywords: String = "",
    val views: Long = 0,
    /** Documented as a float; the live JSON feed sends it as a string. */
    val rate: JsonNode? = null,
    @JsonProperty("length_sec") val lengthSeconds: Int = 0,
    /** Official iframe URL returned by the search and id methods. */
    val embed: String = "",
    @JsonProperty("default_thumb") val defaultThumb: EpornerThumb? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpornerThumb(
    val src: String? = null,
)
