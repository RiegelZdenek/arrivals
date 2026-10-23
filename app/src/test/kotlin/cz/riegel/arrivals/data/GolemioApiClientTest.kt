package cz.riegel.arrivals.data

import cz.riegel.arrivals.data.model.GolemioDepartureBoardResponse
import cz.riegel.arrivals.data.model.GolemioException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GolemioApiClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiClient: GolemioApiClient

    private val sampleJson = """
    {
      "stops": [
        {
          "stop_id": "U316Z101P",
          "stop_name": "I. P. Pavlova",
          "platform_code": "A",
          "stop_lat": 50.0754,
          "stop_lon": 14.4301,
          "zone_id": "P"
        },
        {
          "stop_id": "U316Z102P",
          "stop_name": "I. P. Pavlova",
          "platform_code": "B",
          "stop_lat": 50.0756,
          "stop_lon": 14.4303,
          "zone_id": "P"
        }
      ],
      "departures": [
        {
          "departure_timestamp": {
            "scheduled": "2026-10-23T16:30:00+02:00",
            "predicted": "2026-10-23T16:32:00+02:00",
            "minutes": 2
          },
          "stop": {
            "id": "U316Z101P",
            "name": "I. P. Pavlova",
            "platform_code": "A"
          },
          "route": {
            "short_name": "22",
            "type": 0,
            "is_night": false
          },
          "trip": {
            "headsign": "Bílá Hora",
            "is_canceled": false,
            "is_wheelchair_accessible": true
          },
          "delay": {
            "is_available": true,
            "minutes": 2,
            "seconds": 120
          }
        }
      ]
    }
    """.trimIndent()

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        apiClient = GolemioApiClient(
            baseUrl = mockWebServer.url("/").toString(),
            apiKeyProvider = { "test-api-token-12345" },
            okHttpClient = OkHttpClient.Builder().build()
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun getDepartures_sendsRequiredHeaderAndQueryParams_andParsesResponse() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(sampleJson)
        )

        val result = apiClient.getDepartures(lat = 50.0755, lon = 14.4302, radiusMeters = 300, limit = 10)

        assertTrue("Expected successful result", result.isSuccess)
        val response = result.getOrThrow()
        assertEquals(2, response.stops.size)
        assertEquals("I. P. Pavlova", response.stops[0].stopName)
        assertEquals("A", response.stops[0].platformCode)
        assertEquals(1, response.departures.size)

        val dep = response.departures[0]
        assertEquals("22", dep.route.shortName)
        assertEquals(0, dep.route.type)
        assertEquals("Bílá Hora", dep.trip.headsign)
        assertEquals(2, dep.delay.minutes)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("test-api-token-12345", recordedRequest.getHeader("X-Access-Token"))
        val requestUrl = recordedRequest.requestUrl!!
        assertEquals("50.0755", requestUrl.queryParameter("lat"))
        assertEquals("14.4302", requestUrl.queryParameter("lng"))
        assertEquals("300", requestUrl.queryParameter("range"))
        assertEquals("10", requestUrl.queryParameter("limit"))
    }

    @Test
    fun getDepartures_handlesHttp401Unauthorized() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val result = apiClient.getDepartures(50.0, 14.0)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error should be Unauthorized", error is GolemioException.Unauthorized)
    }

    @Test
    fun getDepartures_handlesHttp429RateLimit() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(429))

        val result = apiClient.getDepartures(50.0, 14.0)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error should be RateLimited", error is GolemioException.RateLimited)
    }
}
