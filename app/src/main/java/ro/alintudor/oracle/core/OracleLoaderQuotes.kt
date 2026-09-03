package ro.alintudor.oracle.core

/**
 * Shared investor-quote pool used by loading screens across the app — the
 * GROWTH calculation loader and the app boot loader both rotate through this
 * exact same set, so the two loaders feel like one consistent experience.
 * Local strings only; no network request is made to show them.
 */
object OracleLoaderQuotes {
    val ALL = listOf(
        "\"Price is what you pay; value is what you get.\"\n— Benjamin Graham",
        "\"Rule No. 1: Never lose money. Rule No. 2: Never forget Rule No. 1.\"\n— Warren Buffett",
        "\"The most important quality for an investor is temperament, not intellect.\"\n— Warren Buffett",
        "\"It's only when the tide goes out that you learn who's been swimming naked.\"\n— Warren Buffett",
        "\"In the short run, the market is a voting machine, but in the long run it is a weighing machine.\"\n— Benjamin Graham",
        "\"The intelligent investor is a realist who sells to optimists and buys from pessimists.\"\n— Benjamin Graham",
        "\"Invert, always invert.\"\n— Charlie Munger",
        "\"Behind every stock is a company. Find out what it's doing.\"\n— Peter Lynch",
        "\"The four most dangerous words in investing are: this time it's different.\"\n— Sir John Templeton"
    )
}
