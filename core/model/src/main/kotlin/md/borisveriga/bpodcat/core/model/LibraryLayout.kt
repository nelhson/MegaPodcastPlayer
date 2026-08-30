package md.borisveriga.bpodcat.core.model

/**
 * How the library draws its shows.
 *
 * A preference rather than a screen-size decision: which one is better depends on how many shows
 * are followed and how recognisable their covers are, and only the user knows that. It is stored,
 * because a layout that resets on every process death is a layout the user has to keep re-choosing.
 */
enum class LibraryLayout {

    /** Artwork tiles in an adaptive grid; the cover is how a show is recognised. */
    GRID,

    /** One row per show, with author and counts; denser, and readable at a glance. */
    LIST,
    ;

    /** The other layout, for a control that simply toggles between the two. */
    val toggled: LibraryLayout get() = if (this == GRID) LIST else GRID

    companion object {

        /**
         * What a library with no stored preference uses.
         *
         * Grid, because a new library is a small one, and a wall of covers is both the faster way
         * to recognise a show and the better first impression than a list of three rows.
         */
        val DEFAULT: LibraryLayout = GRID
    }
}
