// Copyright (c) 2026 Lumora (LumoraPlayer) - original work, noncommercial licence.
package com.lumora

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.lumora.scraper.utils.TMDb3
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbMultiParseTest {

    @Test
    fun `search multi results deserialize to MultiItem`() {
        val gson = GsonBuilder()
            .registerTypeAdapter(TMDb3.MultiItem::class.java, TMDb3.MultiItem.Deserializer())
            .create()
        val json = """
            {"page":1,"results":[
              {"media_type":"movie","id":1,"title":"A Film","overview":"o","popularity":1.0,
               "vote_count":10,"vote_average":7.0,"video":false,"adult":false,
               "genre_ids":[1],"original_title":"A Film","original_language":"en",
               "poster_path":null,"backdrop_path":null,"release_date":"2026-01-01"},
              {"media_type":"tv","id":2,"name":"A Show","overview":"o","popularity":1.0,
               "vote_count":10,"vote_average":7.0,"adult":false,"genre_ids":[1],
               "original_name":"A Show","original_language":"en","origin_country":["US"],
               "poster_path":null,"backdrop_path":null,"first_air_date":"2026-01-01"}
            ],"total_pages":1,"total_results":2}
        """.trimIndent()
        val type = object : TypeToken<TMDb3.PageResult<TMDb3.MultiItem>>() {}.type
        val page: TMDb3.PageResult<TMDb3.MultiItem> = gson.fromJson(json, type)
        var movies = 0
        var shows = 0
        for (item in page.results) {
            when (item) {
                is TMDb3.Movie -> movies++
                is TMDb3.Tv -> shows++
                else -> error("unexpected ${item.javaClass.name}")
            }
        }
        assertEquals(1, movies)
        assertEquals(1, shows)
    }
}
