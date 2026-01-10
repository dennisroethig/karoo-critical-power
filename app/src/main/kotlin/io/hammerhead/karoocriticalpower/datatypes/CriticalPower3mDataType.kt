package io.hammerhead.karoocriticalpower.datatypes

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karoocriticalpower.data.PowerCurveRepository

class CriticalPower3mDataType(
    extensionId: String,
    karooSystem: KarooSystemService,
    powerCurveRepository: PowerCurveRepository,
    showPrComparison: () -> Boolean
) : CriticalPowerDataType(
    extensionId = extensionId,
    karooSystem = karooSystem,
    durationSeconds = 180,
    typeIdSuffix = "3m",
    powerCurveRepository = powerCurveRepository,
    showPrComparison = showPrComparison
)
