package com.blaisegg.idfwidget.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/** Réponse SIRI-Lite de /stop-monitoring (seuls les champs utiles sont mappés). */
@Serializable
data class SiriResponse(@SerialName("Siri") val siri: SiriDto? = null)

@Serializable
data class SiriDto(@SerialName("ServiceDelivery") val serviceDelivery: ServiceDeliveryDto? = null)

@Serializable
data class ServiceDeliveryDto(
    @SerialName("StopMonitoringDelivery") val deliveries: List<StopMonitoringDeliveryDto> = emptyList(),
)

@Serializable
data class StopMonitoringDeliveryDto(
    @SerialName("MonitoredStopVisit") val visits: List<MonitoredStopVisitDto> = emptyList(),
)

@Serializable
data class MonitoredStopVisitDto(
    @SerialName("MonitoredVehicleJourney") val journey: VehicleJourneyDto? = null,
)

@Serializable
data class VehicleJourneyDto(
    @SerialName("LineRef") val lineRef: ValueDto? = null,
    @Serializable(with = ValueListSerializer::class)
    @SerialName("DirectionName") val directionName: List<ValueDto> = emptyList(),
    @Serializable(with = ValueListSerializer::class)
    @SerialName("DestinationName") val destinationName: List<ValueDto> = emptyList(),
    @Serializable(with = ValueListSerializer::class)
    @SerialName("JourneyNote") val journeyNote: List<ValueDto> = emptyList(), // code mission RER
    @SerialName("MonitoredCall") val call: MonitoredCallDto? = null,
)

@Serializable
data class MonitoredCallDto(
    @SerialName("ExpectedDepartureTime") val expectedDeparture: String? = null,
    @SerialName("ExpectedArrivalTime") val expectedArrival: String? = null,
    @SerialName("AimedDepartureTime") val aimedDeparture: String? = null,
    @SerialName("AimedArrivalTime") val aimedArrival: String? = null,
    @Serializable(with = ValueListSerializer::class)
    @SerialName("DestinationDisplay") val destinationDisplay: List<ValueDto> = emptyList(),
    @SerialName("VehicleAtStop") val vehicleAtStop: Boolean? = null,
    @SerialName("DepartureStatus") val departureStatus: String? = null,
    @SerialName("ArrivalStatus") val arrivalStatus: String? = null,
)

@Serializable
data class ValueDto(val value: String = "")

/** Tolère `[{"value":..}]`, `{"value":..}` ou `".."` : le format varie selon les opérateurs. */
object ValueListSerializer : JsonTransformingSerializer<List<ValueDto>>(
    ListSerializer(ValueDto.serializer()),
) {
    private fun wrap(e: JsonElement): JsonElement =
        if (e is JsonPrimitive) JsonObject(mapOf("value" to e)) else e

    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map(::wrap))
        else -> JsonArray(listOf(wrap(element)))
    }
}

fun List<ValueDto>.firstValue(): String? = firstOrNull { it.value.isNotBlank() }?.value
