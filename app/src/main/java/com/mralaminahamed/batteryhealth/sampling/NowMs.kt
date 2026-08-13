package com.mralaminahamed.batteryhealth.sampling

/** Injected clock, so retention and session boundaries are testable at fixed instants. */
fun interface NowMs {
    fun get(): Long
}
