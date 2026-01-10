package io.hammerhead.karoocriticalpower.datatypes

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karoocriticalpower.data.PowerCurveRepository

class CriticalPower20mDataType(
    extensionId: String,
    karooSystem: KarooSystemService,
    powerCurveRepository: PowerCurveRepository,
    showPrComparison: () -> Boolean
) : CriticalPowerDataType(
    extensionId = extensionId,
    karooSystem = karooSystem,
    durationSeconds = 1200,
    typeIdSuffix = "20m",
    powerCurveRepository = powerCurveRepository,
    showPrComparison = showPrComparison
)
