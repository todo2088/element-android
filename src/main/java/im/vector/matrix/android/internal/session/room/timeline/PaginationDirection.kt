package im.vector.matrix.android.internal.session.room.timeline

internal enum class PaginationDirection(val value: String) {
    /**
     * Forwards when the event is added to the end of the timeline.
     * These events come from the /sync stream or from forwards pagination.
     */
    FORWARDS("f"),

    /**
     * Backwards when the event is added to the start of the timeline.
     * These events come from a back pagination.
     */
    BACKWARDS("b");

    val incrementStateIndex: Int by lazy {
        when (this) {
            FORWARDS  -> 1
            BACKWARDS -> -1
        }
    }

}