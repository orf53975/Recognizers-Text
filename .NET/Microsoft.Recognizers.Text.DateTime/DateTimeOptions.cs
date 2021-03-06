﻿using System;

namespace Microsoft.Recognizers.Text.DateTime
{
    [Flags]
    public enum DateTimeOptions
    {
        None = 0,
        SkipFromToMerge = 1,
        SplitDateAndTime = 2,
        CalendarMode = 4,
        ExtendedTypes = 8,
        ExperimentalMode = 4194304, // 2 ^22
        EnablePreview = 8388608, // 2 ^23
    }
}
