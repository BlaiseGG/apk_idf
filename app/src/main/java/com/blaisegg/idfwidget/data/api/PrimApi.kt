package com.blaisegg.idfwidget.data.api

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * API PRIM Île-de-France Mobilités.
 * Base : https://prim.iledefrance-mobilites.fr/marketplace/
 * Authentification : en-tête HTTP `apikey`.
 */
interface PrimApi {

    // ---- Référentiel (Navitia via PRIM) ----

    @GET("v2/navitia/places")
    suspend fun searchPlaces(
        @Header("apikey") apiKey: String,
        @Query("q") query: String,
        @Query("type[]") type: String = "stop_area",
        @Query("count") count: Int = 30,
        @Query("depth") depth: Int = 2, // inclut les modes desservis par l'arrêt
    ): PlacesResponse

    @GET("v2/navitia/coord/{lon};{lat}/places_nearby")
    suspend fun placesNearby(
        @Header("apikey") apiKey: String,
        @Path("lon") lon: Double,
        @Path("lat") lat: Double,
        @Query("type[]") type: String = "stop_area",
        @Query("distance") distance: Int = 1500,
        @Query("count") count: Int = 60, // large : les arrêts de bus sont nombreux et peuvent être filtrés
        @Query("depth") depth: Int = 2,
    ): PlacesNearbyResponse

    @GET("v2/navitia/stop_areas/{stopArea}/lines")
    suspend fun linesOfStopArea(
        @Header("apikey") apiKey: String,
        @Path("stopArea") stopAreaId: String,
        @Query("count") count: Int = 100,
        @Query("depth") depth: Int = 1,
    ): LinesResponse

    @GET("v2/navitia/stop_areas/{stopArea}/lines/{line}/routes")
    suspend fun routes(
        @Header("apikey") apiKey: String,
        @Path("stopArea") stopAreaId: String,
        @Path("line") lineId: String,
        @Query("count") count: Int = 100,
    ): RoutesResponse

    @GET("v2/navitia/stop_areas/{stopArea}/lines/{line}/stop_points")
    suspend fun stopPoints(
        @Header("apikey") apiKey: String,
        @Path("stopArea") stopAreaId: String,
        @Path("line") lineId: String,
        @Query("count") count: Int = 50,
    ): StopPointsResponse

    /** Quelques circulations d'un parcours, avec leurs arrêts dans l'ordre (pour savoir s'il va vers Paris). */
    @GET("v2/navitia/routes/{route}/vehicle_journeys")
    suspend fun routeVehicleJourneys(
        @Header("apikey") apiKey: String,
        @Path("route") routeId: String,
        @Query("count") count: Int = 12,
        @Query("depth") depth: Int = 2,
    ): VehicleJourneysResponse

    /** Prochains départs théoriques de la ligne à l'arrêt (avec code mission et circulation). */
    @GET("v2/navitia/stop_areas/{stopArea}/lines/{line}/departures")
    suspend fun departures(
        @Header("apikey") apiKey: String,
        @Path("stopArea") stopAreaId: String,
        @Path("line") lineId: String,
        @Query("count") count: Int = 80,
        @Query("data_freshness") dataFreshness: String = "base_schedule",
        @Query("depth") depth: Int = 1,
    ): DeparturesResponse

    /** Une circulation précise, avec tous ses arrêts dans l'ordre. */
    @GET("v2/navitia/vehicle_journeys/{vj}")
    suspend fun vehicleJourney(
        @Header("apikey") apiKey: String,
        @Path("vj") vehicleJourneyId: String,
        @Query("depth") depth: Int = 2,
    ): VehicleJourneysResponse

    // ---- Temps réel (SIRI-Lite, requête unitaire) ----

    @GET("stop-monitoring")
    suspend fun stopMonitoring(
        @Header("apikey") apiKey: String,
        @Query("MonitoringRef") monitoringRef: String,
    ): SiriResponse

    companion object {
        const val BASE_URL = "https://prim.iledefrance-mobilites.fr/marketplace/"
    }
}
