package cz.riegel.arrivals.data

import cz.riegel.arrivals.data.model.GolemioException
import cz.riegel.arrivals.domain.model.Coordinates
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class GolemioApiClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiClient: TransitDataSource

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
            baseUrl = mockWebServer.url("/v2/pid/departureboards").toString(),
            apiKeyProvider = { "test-api-token-12345" },
            okHttpClient = OkHttpClient.Builder().build()
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun fetchNearbyDepartures_sendsRequiredHeaderAndQueryParams_andParsesResponse() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(sampleJson)
        )

        val result = apiClient.fetchNearbyDepartures(
            coordinates = Coordinates(lat = 50.0755, lon = 14.4302),
            radiusMeters = 300,
            limit = 10
        )

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
        assertEquals("10", requestUrl.queryParameter("limit"))
        assertEquals("I. P. Pavlova", requestUrl.queryParameter("names"))
    }

    @Test
    fun fetchNearbyDepartures_resolvesUtf8PragueStopsFromInjectedStream() = runBlocking {
        val utf8StopsJson = "\uFEFF[{\"n\":\"Lipanská\",\"lat\":50.08396,\"lon\":14.45343},{\"n\":\"Jiřího z Poděbrad\",\"lat\":50.0778,\"lon\":14.4504}]"
        val customClient: TransitDataSource = GolemioApiClient(
            baseUrl = mockWebServer.url("/v2/pid/departureboards").toString(),
            apiKeyProvider = { "test-api-token-12345" },
            okHttpClient = OkHttpClient.Builder().build(),
            stopsStreamProvider = { ByteArrayInputStream(utf8StopsJson.toByteArray(Charsets.UTF_8)) }
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(sampleJson)
        )

        val result = customClient.fetchNearbyDepartures(
            coordinates = Coordinates(lat = 50.08396, lon = 14.45343),
            radiusMeters = 300,
            limit = 15
        )
        assertTrue(result.isSuccess)

        val recordedRequest = mockWebServer.takeRequest()
        val requestUrl = recordedRequest.requestUrl!!
        assertEquals("15", requestUrl.queryParameter("limit"))
        assertEquals(listOf("Lipanská"), requestUrl.queryParameterValues("names"))
    }

    @Test
    fun fetchDeparturesForStops_sendsNamesParam() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(sampleJson)
        )

        val result = apiClient.fetchDeparturesForStops(listOf("Hlavní nádraží"), limit = 1)
        assertTrue(result.isSuccess)

        val recordedRequest = mockWebServer.takeRequest()
        val requestUrl = recordedRequest.requestUrl!!
        assertEquals("1", requestUrl.queryParameter("limit"))
        assertEquals("Hlavní nádraží", requestUrl.queryParameter("names"))
    }

    @Test
    fun fetchDeparturesForStops_handlesStringLessThanOneMinute() = runBlocking {
        val stringMinutesJson = """
        {
          "stops": [],
          "departures": [
            {
              "departure_timestamp": {
                "scheduled": "2026-10-26T15:50:00+01:00",
                "predicted": "2026-10-26T15:50:00+01:00",
                "minutes": "<1"
              },
              "stop": { "id": "U142Z1", "name": "Hlavní nádraží" },
              "route": { "short_name": "9", "type": 0 },
              "trip": { "headsign": "Sídliště Řepy" },
              "delay": { "is_available": true, "minutes": 0 }
            }
          ]
        }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(stringMinutesJson)
        )

        val result = apiClient.fetchDeparturesForStops(listOf("Hlavní nádraží"), limit = 1)
        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals(1, response.departures.size)
        assertEquals("<1", response.departures[0].departureTimestamp.minutes)
    }

    @Test
    fun fetchNearbyDepartures_handlesHttp401Unauthorized() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val result = apiClient.fetchNearbyDepartures(Coordinates(50.0, 14.0), radiusMeters = 300)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error should be Unauthorized", error is GolemioException.Unauthorized)
    }

    @Test
    fun fetchNearbyDepartures_handlesHttp429RateLimit() = runBlocking {
        mockWebServer.enqueue(MockResponse().setResponseCode(429))

        val result = apiClient.fetchNearbyDepartures(Coordinates(50.0, 14.0), radiusMeters = 300)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Error should be RateLimited", error is GolemioException.RateLimited)
    }
}
