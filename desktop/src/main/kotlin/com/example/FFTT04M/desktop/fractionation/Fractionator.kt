package com.example.FFTT04M.desktop.fractionation

data class Segment(
    val startMs: Int,
    val endMs: Int,
    val label: String? = null,
    val clusterId: Int? = null,
)

interface Fractionator {
    val name: String
    fun fractionate(x: FloatArray, sr: Int): List<Segment>
}
