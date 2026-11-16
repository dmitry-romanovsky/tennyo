package app.tennyo.mobile.feature.terminal

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalInputDispatcherTest {
  @Test
  fun rapidInputIsBatchedInOrder(): Unit = runBlocking {
    val sent = mutableListOf<Pair<String, String>>()
    val dispatcher = TerminalInputDispatcher(
      scope = this,
      sendInput = { terminalId, text -> sent += terminalId to text },
      onFailure = {},
    )

    dispatcher.send("terminal", "a")
    yield()
    dispatcher.send("terminal", "b")
    yield()
    dispatcher.send("terminal", "c")
    delay(INPUT_BATCH_TEST_DELAY_MILLIS)

    assertEquals(listOf("terminal" to "abc"), sent)
  }

  @Test
  fun terminalSwitchClearsBufferedInputForPreviousTerminal(): Unit = runBlocking {
    val sent = mutableListOf<Pair<String, String>>()
    val dispatcher = TerminalInputDispatcher(
      scope = this,
      sendInput = { terminalId, text -> sent += terminalId to text },
      onFailure = {},
    )

    dispatcher.send("first", "a")
    yield()
    dispatcher.send("second", "b")
    delay(INPUT_BATCH_TEST_DELAY_MILLIS)

    assertEquals(listOf("second" to "b"), sent)
  }

  @Test
  fun failureClearsBufferedInputAndReportsOnce(): Unit = runBlocking {
    val failures = mutableListOf<Throwable>()
    var attempts = 0
    val dispatcher = TerminalInputDispatcher(
      scope = this,
      sendInput = { _, _ ->
        attempts += 1
        error("boom")
      },
      onFailure = { failures += it },
    )

    dispatcher.send("terminal", "a")
    dispatcher.send("terminal", "b")
    delay(INPUT_BATCH_TEST_DELAY_MILLIS)

    assertEquals(1, attempts)
    assertEquals(1, failures.size)
  }
}

private const val INPUT_BATCH_TEST_DELAY_MILLIS = 80L
