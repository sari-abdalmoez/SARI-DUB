package com.saridub.app

/** Central TMDB authentication. Accepts either a v3 API key or the Read Access Token JWT. */
object TmdbApi {
    private fun credential(): String = Config.TMDB_KEY.trim()

    private fun usesBearerToken(): Boolean = credential().split('.').size == 3

    private fun headers(): Map<String, String> {
        val token = credential()
        return if (token.isNotBlank() && usesBearerToken()) {
            mapOf("Authorization" to "Bearer $token")
        } else {
            emptyMap()
        }
    }

    fun url(path: String, params: Map<String, String> = emptyMap()): String {
        val all = LinkedHashMap(params)
        val key = credential()
        if (key.isNotBlank() && !usesBearerToken()) all["api_key"] = key

        val query = all.entries.joinToString("&") { (name, value) ->
            "${Net.enc(name)}=${Net.enc(value)}"
        }
        val base = "https://api.themoviedb.org/3/${path.trimStart('/')}"
        return if (query.isEmpty()) base else "$base?$query"
    }

    fun get(path: String, params: Map<String, String> = emptyMap(), timeout: Int = 8000): String {
        if (credential().isBlank()) {
            throw ApiException(ErrKind.MISSING_KEY, "TMDB_KEY is not set")
        }
        return Net.get(url(path, params), headers(), timeout)
    }
}
