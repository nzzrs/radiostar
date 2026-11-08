/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommend

/**
 * A major or minor key, identified by pitch class (C = 0).
 *
 * [camelot] maps it to the DJ wheel so two tracks can be compared without
 * knowing music theory: same number is compatible, one step away is a safe
 * transition, anything else is a jump.
 */
class MusicalKey(
    pitchClass: Int,
    val minor: Boolean,
) {
    private val rawPitchClass = pitchClass

    val pitchClass: Int = Math.floorMod(pitchClass, 12)

    val name: String
        get() = "${PITCH_CLASSES[pitchClass]} ${if (minor) "minor" else "major"}"

    val camelot: Int
        get() = if (minor) MINOR_CAMELOT[pitchClass] else MAJOR_CAMELOT[pitchClass]

    fun isValid(): Boolean = rawPitchClass in 0..11

    fun harmonicDistance(other: MusicalKey): Int {
        if (pitchClass == other.pitchClass && minor == other.minor) return 0
        if (camelot == other.camelot) return 1
        val difference = kotlin.math.abs(camelot - other.camelot)
        val wrapped = minOf(difference, 12 - difference)
        return if (wrapped == 1) 1 else 2
    }

    override fun equals(other: Any?): Boolean =
        other is MusicalKey && pitchClass == other.pitchClass && minor == other.minor

    override fun hashCode(): Int = pitchClass * 2 + if (minor) 1 else 0

    override fun toString(): String = name

    private companion object {
        val PITCH_CLASSES =
            listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        val MAJOR_CAMELOT = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)
        val MINOR_CAMELOT = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)
    }
}
