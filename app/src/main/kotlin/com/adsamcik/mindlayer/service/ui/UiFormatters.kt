package com.adsamcik.mindlayer.service.ui

import java.text.NumberFormat

internal fun formatWholeNumber(value: Int): String = NumberFormat.getIntegerInstance().format(value)

internal fun formatWholeNumber(value: Long): String = NumberFormat.getIntegerInstance().format(value)
