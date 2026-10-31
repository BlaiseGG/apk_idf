package com.blaisegg.idfwidget.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlacesResponse(val places: List<PlaceDto> = emptyList())

@Serializable
data class PlacesNearbyResponse(
    @SerialName("places_nearby") val placesNearby: List<PlaceDto> = emptyList(),
)

@Serializable
data class PlaceDto(
    val id: String = "",
    val name: String = "",
    @SerialName("embedded_type") val embeddedType: String? = null,
    val distance: String? = null,
    @SerialName("stop_area") val stopArea: StopAreaDto? = null,
)

@Serializable
data class StopAreaDto(
    val id: String = "",
    val name: String = "",
    val label: String? = null,
)

@Serializable
data class LinesResponse(val lines: List<LineDto> = emptyList())

@Serializable
data class LineDto(
    val id: String = "",
    val name: String = "",
    val code: String = "",
    val color: String? = null,
    @SerialName("text_color") val textColor: String? = null,
    @SerialName("commercial_mode") val commercialMode: NamedDto? = null,
    @SerialName("physical_modes") val physicalModes: List<NamedDto> = emptyList(),
)

@Serializable
data class NamedDto(val id: String = "", val name: String = "")

@Serializable
data class RoutesResponse(val routes: List<RouteDto> = emptyList())

@Serializable
data class RouteDto(
    val id: String = "",
    val name: String = "",
    val direction: RouteDirectionDto? = null,
)

@Serializable
data class RouteDirectionDto(
    val id: String = "",
    val name: String = "",
    @SerialName("stop_area") val stopArea: StopAreaDto? = null,
)

@Serializable
data class StopPointsResponse(@SerialName("stop_points") val stopPoints: List<StopPointDto> = emptyList())

@Serializable
data class StopPointDto(val id: String = "", val name: String = "")
