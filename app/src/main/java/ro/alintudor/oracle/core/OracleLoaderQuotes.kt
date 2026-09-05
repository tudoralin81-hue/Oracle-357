package ro.alintudor.oracle.core

/**
 * Shared investor-quote pool used by loading screens across the app — the
 * GROWTH calculation loader and the app boot loader both rotate through this
 * exact same set, so the two loaders feel like one consistent experience.
 * Local strings only; no network request is made to show them.
 *
 * Quote and author are kept separate (rather than one combined string) so
 * each loader can render the author in its own distinct color instead of
 * the whole line reading as one undifferentiated block of text.
 */
object OracleLoaderQuotes {
    data class Quote(val text: String, val author: String)

    val ALL = listOf(
        Quote("\"Price is what you pay; value is what you get.\"", "Benjamin Graham"),
        Quote("\"Rule No. 1: Never lose money. Rule No. 2: Never forget Rule No. 1.\"", "Warren Buffett"),
        Quote("\"The most important quality for an investor is temperament, not intellect.\"", "Warren Buffett"),
        Quote("\"It's only when the tide goes out that you learn who's been swimming naked.\"", "Warren Buffett"),
        Quote("\"In the short run, the market is a voting machine, but in the long run it is a weighing machine.\"", "Benjamin Graham"),
        Quote("\"The intelligent investor is a realist who sells to optimists and buys from pessimists.\"", "Benjamin Graham"),
        Quote("\"Invert, always invert.\"", "Charlie Munger"),
        Quote("\"Behind every stock is a company. Find out what it's doing.\"", "Peter Lynch"),
        Quote("\"The four most dangerous words in investing are: this time it's different.\"", "Sir John Templeton"),
    )

    /** A random quote, guaranteed different from [excluding] when more than one exists. */
    fun random(excluding: Quote? = null): Quote {
        if (ALL.size <= 1) return ALL.first()
        var next = ALL.random()
        while (next == excluding) next = ALL.random()
        return next
    }

    /** Two-color rendering: the quote body in [quoteColor], the attribution
     *  ("Author") in [authorColor], as one line-broken CharSequence. */
    fun spanned(quote: Quote, quoteColor: Int, authorColor: Int): CharSequence {
        val builder = android.text.SpannableStringBuilder()
        builder.append(quote.text)
        val breakAt = builder.length
        builder.append("\n").append(quote.author)
        builder.setSpan(android.text.style.ForegroundColorSpan(quoteColor), 0, breakAt, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(android.text.style.ForegroundColorSpan(authorColor), breakAt, builder.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return builder
    }
}
