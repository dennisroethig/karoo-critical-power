package io.hammerhead.karoocriticalpower.datatypes

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karoocriticalpower.data.PowerCurveRepository

class CriticalPower45mDataType(
    extensionId: String,
    karooSystem: KarooSystemService,
    powerCurveRepository: PowerCurveRepository,
    showPrComparison: () -> Boolean
) : CriticalPowerDataType(
    extensionId = extensionId,
    karooSystem = karooSystem,
    durationSeconds = 2700,
    typeIdSuffix = "45m",
    powerCurveRepository = powerCurveRepository,
    showPrComparison = showPrComparison
)
