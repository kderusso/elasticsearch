% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

**Description**

Converts an input to a nanosecond-resolution date value (aka date_nanos).

::::{note}
The range for date nanos is 1970-01-01T00:00:00.000000000Z to 2262-04-11T23:47:16.854775807Z, attempting to convert values outside of that range will result in null with a warning.  Additionally, integers cannot be converted into date nanos, as the range of integer nanoseconds only covers about 2 seconds after epoch.
::::


