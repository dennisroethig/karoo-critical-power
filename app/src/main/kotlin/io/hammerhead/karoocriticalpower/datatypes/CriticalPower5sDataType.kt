package io.hammerhead.karoocriticalpower.datatypes

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karoocriticalpower.data.PowerCurveRepository

/**
 * Best 5-second average power data type.
 */
class CriticalPower5sDataType(
    extensionId: String,
    karooSystem: KarooSystemService,
    powerCurveRepository: PowerCurveRepository,
    showPrComparison: () -> Boolean
) : CriticalPowerDataType(
    extensionId = extensionId,
    karooSystem = karooSystem,
    durationSeconds = 5,
    typeIdSuffix = "5s",
    powerCurveRepository = powerCurveRepository,
    showPrComparison = showPrComparison
)
