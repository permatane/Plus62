// use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove them
    description = "AnyMovies - Downloads-Anymovies.co provider for CloudStream. Thousands of Hollywood movies & TV shows with multiple streaming servers."
    language = "en"
    authors = listOf("AnyMovies")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3 // will be 3 if unspecified

    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=https://www.downloads-anymovies.co&sz=%size%"
}
