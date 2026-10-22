package com.tegenwind.app.ui.rides

import com.tegenwind.app.data.SegmentTraversalEntity

// What the end-of-ride card on a ride's page says, kept apart from the screen so it can be tested.

/** Where a ride's time falls among a route's rides: [place] 1 is the fastest, of [of]. Ties share a place. */
data class Place(val place: Int, val of: Int) {
    val fastest: Boolean get() = place == 1 && of > 1
    val slowest: Boolean get() = place == of && of > 1
}

/** The place of [mine] (start-to-finish ms) among [all] (the route's rides, this one included). Null when it isn't among them. */
fun placeAmong(mine: Long, all: List<Long>): Place? {
    if (mine !in all) return null
    return Place(all.count { it < mine } + 1, all.size)
}

/** "1st", "2nd", "3rd", "4th", "11th", "22nd". */
fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

/** "fastest yet", "slowest yet", or "3rd fastest of 42". */
fun placeText(p: Place): String = when {
    p.of == 1 -> "first ride on this route"
    p.fastest -> "fastest yet"
    p.slowest -> "slowest yet"
    else -> "${ordinal(p.place)} fastest of ${p.of}"
}

/**
 * Seconds this ride was ahead (+) or behind (−) a reference: an earlier ride's start-to-finish time
 * ([otherMs] against [mineMs]), or the arrival predicted at the start against the actual one.
 */
fun aheadS(mineMs: Long, otherMs: Long): Double = (otherMs - mineMs) / 1000.0

/**
 * The segments where this ride's pass ([mine]) was the quickest yet, start to finish over the
 * segment, against the passes of rides before it ([earlier]). A segment needs [minEarlier] earlier
 * passes before a record means anything. Segment indexes, in order.
 */
fun recordSegments(mine: List<SegmentTraversalEntity>, earlier: List<SegmentTraversalEntity>, minEarlier: Int = 3): List<Int> {
    val before = earlier.groupBy { it.segIdx }
    return mine.filter { p ->
        val others = before[p.segIdx].orEmpty()
        others.size >= minEarlier && others.all { (p.exitMs - p.enterMs) < (it.exitMs - it.enterMs) }
    }.map { it.segIdx }.sorted()
}
