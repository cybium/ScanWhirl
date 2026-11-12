package com.scanwhirl.app.util

/**
 * Brand slice 3: decides when the Home mark may "whirl" — one 220 ms half-turn
 * of its rings, played once when a document has actually landed in the library
 * (scan saved, import, duplicate, merge). The library read only lists completed
 * saves, so this never celebrates a save that has not happened.
 *
 * Pure state, no Android types, so it is unit-testable. The caller still owns
 * the motion gate ([SwMotion.ambientAllowed]) and cancellation.
 */
class BrandWhirl {
    private var knownIds: Set<String>? = null
    private var knownVault: Boolean? = null

    /**
     * Records the library just loaded and returns true when it contains a
     * document id the previous load of the *same* library (normal or Private)
     * did not. The first load and a normal/Private switch never whirl.
     */
    fun onLibraryLoaded(ids: Set<String>, vault: Boolean): Boolean {
        val previous = knownIds
        val sameLibrary = knownVault == vault
        knownIds = ids
        knownVault = vault
        return previous != null && sameLibrary && ids.any { it !in previous }
    }
}
