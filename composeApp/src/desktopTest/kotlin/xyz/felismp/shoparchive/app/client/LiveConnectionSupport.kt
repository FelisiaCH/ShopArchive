package xyz.felismp.shoparchive.app.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** Starts collecting immediately so no emission is missed by a test. */
fun <T> CoroutineScope.launchCollect(flow: SharedFlow<T>, f: (T) -> Unit): Job =
    launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { f(it) } }
