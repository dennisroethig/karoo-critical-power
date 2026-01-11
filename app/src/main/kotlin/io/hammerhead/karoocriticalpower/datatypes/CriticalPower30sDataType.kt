package io.hammerhead.karoocriticalpower.datatypes

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karoocriticalpower.PowerBufferManager
import io.hammerhead.karoocriticalpower.data.PowerCurveRepository
import io.hammerhead.karoocriticalpower.data.PrTimeframe

/**
 * Best 30-second average power data type.
 */
class CriticalPower30sDataType(
    extensionId: String,
    karooSystem: KarooSystemService,
    bufferManager: PowerBufferManager,
    powerCurveRepository: PowerCurveRepository,
    showPrComparison: () -> Boolean,
    getPrTimeframe: () -> PrTimeframe,
    isIntervalsConfigured: () -> Boolean
) : CriticalPowerDataType(
    extensionId = extensionId,
    karooSystem = karooSystem,
    durationSeconds = 30,
    typeIdSuffix = "30s",
    bufferManager = bufferManager,
    powerCurveRepository = powerCurveRepository,
    showPrComparison = showPrComparison,
    getPrTimeframe = getPrTimeframe,
    isIntervalsConfigured = isIntervalsConfigured
)
