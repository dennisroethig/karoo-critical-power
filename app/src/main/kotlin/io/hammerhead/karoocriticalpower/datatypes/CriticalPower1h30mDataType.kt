package io.hammerhead.karoocriticalpower.datatypes

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karoocriticalpower.data.PowerCurveRepository

class CriticalPower1h30mDataType(
    extensionId: String,
    karooSystem: KarooSystemService,
    powerCurveRepository: PowerCurveRepository,
    showPrComparison: () -> Boolean
) : CriticalPowerDataType(
    extensionId = extensionId,
    karooSystem = karooSystem,
    durationSeconds = 5400,
    typeIdSuffix = "1h30m",
    powerCurveRepository = powerCurveRepository,
    showPrComparison = showPrComparison
)
