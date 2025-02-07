@file:Suppress("SpreadOperator", "WildcardImport")
package net.corda.uniqueness.checker.impl

import net.corda.crypto.core.SecureHashImpl
import net.corda.crypto.testkit.SecureHashUtils.randomBytes
import net.corda.crypto.testkit.SecureHashUtils.randomSecureHash
import net.corda.data.flow.event.external.ExternalEventContext
import net.corda.data.uniqueness.UniquenessCheckRequestAvro
import net.corda.data.uniqueness.UniquenessCheckResponseAvro
import net.corda.data.uniqueness.UniquenessCheckResultSuccessAvro
import net.corda.test.util.identity.createTestHoldingIdentity
import net.corda.test.util.time.AutoTickTestClock
import net.corda.uniqueness.backingstore.BackingStore
import net.corda.uniqueness.backingstore.impl.fake.BackingStoreImplFake
import net.corda.uniqueness.checker.UniquenessChecker
import net.corda.uniqueness.utils.UniquenessAssertions.assertInputStateConflictResponse
import net.corda.uniqueness.utils.UniquenessAssertions.assertMalformedRequestResponse
import net.corda.uniqueness.utils.UniquenessAssertions.assertReferenceStateConflictResponse
import net.corda.uniqueness.utils.UniquenessAssertions.assertStandardSuccessResponse
import net.corda.uniqueness.utils.UniquenessAssertions.assertTimeWindowOutOfBoundsResponse
import net.corda.uniqueness.utils.UniquenessAssertions.assertUnhandledExceptionResponse
import net.corda.uniqueness.utils.UniquenessAssertions.assertUniqueCommitTimestamps
import net.corda.uniqueness.utils.UniquenessAssertions.assertUnknownInputStateResponse
import net.corda.uniqueness.utils.UniquenessAssertions.assertUnknownReferenceStateResponse
import net.corda.v5.crypto.SecureHash
import net.corda.virtualnode.toAvro
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertAll
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.*
import kotlin.test.assertEquals

/**
 * Unit tests for uniqueness checker implementations. Currently, this tests our single batched
 * uniqueness checker implementation, using a "fake" backing store.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UniquenessCheckerImplTests {

    private val baseTime: Instant = Instant.EPOCH

    private val groupId = UUID.randomUUID().toString()

    // Default holding id used in most tests
    private val defaultHoldingIdentity = createTestHoldingIdentity(
        "C=GB, L=London, O=Alice", groupId).toAvro()

    // We don't use Instant.MAX because this appears to cause a long overflow in Avro
    private val defaultTimeWindowUpperBound: Instant =
        LocalDate.of(2200, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)

    private lateinit var testClock: AutoTickTestClock

    private lateinit var uniquenessChecker: UniquenessChecker

    private lateinit var backingStore: BackingStore

    private fun currentTime(): Instant = testClock.peekTime()

    private fun newRequestBuilder(txId: SecureHash = randomSecureHash()): UniquenessCheckRequestAvro.Builder =
        UniquenessCheckRequestAvro.newBuilder(
            UniquenessCheckRequestAvro(
                defaultHoldingIdentity,
                ExternalEventContext(),
                txId.toString(),
                emptyList(),
                emptyList(),
                0,
                null,
                defaultTimeWindowUpperBound
            )
        )

    private fun processRequests(
        vararg requests: UniquenessCheckRequestAvro
    ) : List<UniquenessCheckResponseAvro> {
        val requestsList = requests.asList()

        val responses = uniquenessChecker.processRequests(requests.asList())

        return requestsList.map { responses[it]!! }
    }

    private fun generateUnspentStates(numOutputStates: Int): List<String> {
        val issueTxId = randomSecureHash()
        val unspentStateRefs = LinkedList<String>()

        repeat(numOutputStates) {
            unspentStateRefs.push("$issueTxId:$it")
        }

        processRequests(
            newRequestBuilder(issueTxId)
                .setNumOutputStates(numOutputStates)
                .build()
        ).let { responses ->
            assertAll(
                { assertThat(responses).hasSize(1) },
                { assertStandardSuccessResponse(responses[0], testClock) }
            )
        }

        return unspentStateRefs
    }

    @BeforeEach
    fun init() {
        /*
         * Specific clock values are important to our testing in some cases, so we use a mock time
         * facilities service which provides a clock starting at a known point in time (baseTime)
         * and will increment its current time by one second on each call. The current time can also
         * be  manipulated by tests directly via [MockTimeFacilities.advanceTime] to change this
         * between calls (e.g. to manipulate time window behavior)
         */
        testClock = AutoTickTestClock(baseTime, Duration.ofSeconds(1))

        backingStore = spy(BackingStoreImplFake(mock()))

        uniquenessChecker = BatchedUniquenessCheckerImpl(
            mock(),
            mock(),
            mock(),
            mock(),
            testClock,
            backingStore)
    }

    @Nested
    inner class MalformedRequests {
        @Test
        fun `Request is missing time window upper bound`() {
            assertThrows(org.apache.avro.AvroRuntimeException::class.java, {
                newRequestBuilder()
                    .setTimeWindowUpperBound(null)
                    .build()
            }, "Field timeWindowUpperBound type:LONG pos:5 does not accept null values")
        }

        @Test
        fun `Request contains a negative number of output states`() {
            processRequests(
                newRequestBuilder()
                    .setNumOutputStates(-1)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    {
                        assertMalformedRequestResponse(
                            responses[0], "Number of output states cannot be less than 0."
                        )
                    },
                )
            }
        }
    }

    @Nested
    inner class InputStates {
        @Test
        fun `Attempting to spend an unknown input state fails`() {
            val inputStateRef = "${randomSecureHash()}:0"

            processRequests(
                newRequestBuilder()
                    .setInputStates(listOf(inputStateRef))
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertUnknownInputStateResponse(responses[0], listOf(inputStateRef)) }
                )
            }
        }

        @Test
        fun `Single tx and single state spend is successful`() {
            processRequests(
                newRequestBuilder()
                    .setInputStates(generateUnspentStates(1))
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }
        }

        @Test
        fun `Single tx and multiple state spends is successful`() {

            processRequests(
                newRequestBuilder()
                    .setInputStates(generateUnspentStates(7))
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }
        }

        @Test
        fun `Single tx and single input state spend retried in same batch is successful`() {
            val request = newRequestBuilder()
                .setInputStates(generateUnspentStates(1))
                .build()

            processRequests(
                request,
                request
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(2) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    { assertStandardSuccessResponse(responses[1], testClock) },
                    // Responses equal (idempotency)
                    { assertEquals(responses[0], responses[1]) }
                )
            }
        }

        @Test
        fun `Single tx and single input state spend retried in different batch is successful`() {
            val request = newRequestBuilder()
                .setInputStates(generateUnspentStates(1))
                .build()

            var initialResponse: UniquenessCheckResponseAvro? = null

            processRequests(
                request
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
                initialResponse = responses[0]
            }

            processRequests(
                request
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    // Responses equal (idempotency)
                    { assertEquals(initialResponse, responses[0]) }
                )
            }
        }

        @Test
        fun `Multiple txs spending single different input states in same batch is successful`() {
            val requests = List(5) {
                newRequestBuilder()
                    .setInputStates(generateUnspentStates(1))
                    .build()
            }

            uniquenessChecker.processRequests(requests).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(5) },
                    { assertStandardSuccessResponse(responses[requests[0]]!!, testClock) },
                    { assertStandardSuccessResponse(responses[requests[1]]!!, testClock) },
                    { assertStandardSuccessResponse(responses[requests[2]]!!, testClock) },
                    { assertStandardSuccessResponse(responses[requests[3]]!!, testClock) },
                    { assertStandardSuccessResponse(responses[requests[4]]!!, testClock) },
                    // Check all tx ids match up to corresponding requests and commit timestamps
                    // are unique
                    { assertIterableEquals(
                        responses.keys.map { it.txId }, responses.values.map { it.txId }) },
                    { assertUniqueCommitTimestamps(responses.values) }
                )
            }
        }

        @Test
        fun `Multiple txs spending single different input states in different batches is successful`() {
            val requests = List(5) {
                newRequestBuilder()
                    .setInputStates(generateUnspentStates(1))
                    .build()
            }

            val allResponses = LinkedList<UniquenessCheckResponseAvro>()

            repeat(5) { count ->
                processRequests(requests[count]).also { responses ->
                    assertAll(
                        { assertThat(responses).hasSize(1) },
                        { assertStandardSuccessResponse(responses[0], testClock) },
                        { assertEquals(requests[count].txId, responses[0].txId) },
                    )
                }.also { responses ->
                    allResponses.add(responses[0])
                }
            }

            assertUniqueCommitTimestamps(allResponses)
        }

        @Test
        fun `Multiple txs spending multiple different input states in same batch is successful`() {
            val requests = listOf(
                newRequestBuilder()
                    .setInputStates(generateUnspentStates(7))
                    .build(),
                newRequestBuilder()
                    .setInputStates(generateUnspentStates(3))
                    .build(),
                newRequestBuilder()
                    .setInputStates(generateUnspentStates(1))
                    .build()
            )

            uniquenessChecker.processRequests(requests).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(3) },
                    { assertStandardSuccessResponse(responses[requests[0]]!!, testClock) },
                    { assertStandardSuccessResponse(responses[requests[1]]!!, testClock) },
                    { assertStandardSuccessResponse(responses[requests[2]]!!, testClock) },
                    // Check all tx ids match up to corresponding requests and commit timestamps
                    // are unique
                    { assertIterableEquals(
                        responses.keys.map { it.txId }, responses.values.map { it.txId }) },
                    { assertUniqueCommitTimestamps(responses.values) }
                )
            }
        }

        @Test
        fun `Multiple txs spending multiple different input states in different batches is successful`() {
            val requests = listOf(
                newRequestBuilder()
                    .setInputStates(generateUnspentStates(7))
                    .build(),
                newRequestBuilder()
                    .setInputStates(generateUnspentStates(3))
                    .build(),
                newRequestBuilder()
                    .setInputStates(generateUnspentStates(1))
                    .build()
            )

            val allResponses = LinkedList<UniquenessCheckResponseAvro>()

            repeat(3) { count ->
                processRequests(requests[count]).also { responses ->
                    assertAll(
                        { assertThat(responses).hasSize(1) },
                        { assertStandardSuccessResponse(responses[0], testClock) },
                        { assertEquals(requests[count].txId, responses[0].txId) },
                    )
                }.also { responses ->
                    allResponses.add(responses[0])
                }
            }

            assertUniqueCommitTimestamps(allResponses)
        }

        @Test
        fun `Multiple txs spending single duplicate input state in same batch fails for second tx`() {

            val sharedState = generateUnspentStates(1)

            processRequests(
                newRequestBuilder()
                    .setInputStates(sharedState)
                    .build(),
                newRequestBuilder()
                    .setInputStates(sharedState)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(2) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    { assertInputStateConflictResponse(responses[1], listOf(sharedState.single())) }
                )
            }
        }

        @Test
        fun `Multiple txs spending single duplicate input state in different batch fails for second tx`() {
            val sharedState = generateUnspentStates(1)

            processRequests(
                newRequestBuilder()
                    .setInputStates(sharedState)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }

            processRequests(
                newRequestBuilder()
                    .setInputStates(sharedState)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertInputStateConflictResponse(responses[0], listOf(sharedState.single())) }
                )
            }
        }

        @Test
        fun `Multiple txs spending multiple duplicate input states in same batch fails for second tx`() {
            val sharedState = List(3) { generateUnspentStates(1).single() }

            processRequests(
                newRequestBuilder()
                    .setInputStates(
                        listOf(
                            sharedState[0],
                            sharedState[1],
                            generateUnspentStates(1).single()
                        )
                    )
                    .build(),
                newRequestBuilder()
                    .setInputStates(
                        listOf(
                            sharedState[0],
                            sharedState[2]
                        )
                    )
                    .build(),
                newRequestBuilder()
                    .setInputStates(
                        listOf(
                            sharedState[2],
                            sharedState[1],
                            sharedState[0]
                        )
                    )
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(3) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    { assertInputStateConflictResponse(responses[1], listOf(sharedState[0])) },
                    {
                        assertInputStateConflictResponse(
                            responses[2],
                            listOf(sharedState[0], sharedState[1])
                        )
                    }
                )
            }
        }

        @Test
        fun `Multiple txs spending multiple duplicate input states in different batch fails for second tx`() {
            val sharedState = List(3) { generateUnspentStates(1).single() }

            processRequests(
                newRequestBuilder()
                    .setInputStates(
                        listOf(
                            sharedState[0],
                            sharedState[1],
                            generateUnspentStates(1).single()
                        )
                    )
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }

            processRequests(
                newRequestBuilder()
                    .setInputStates(
                        listOf(
                            sharedState[0],
                            sharedState[2]
                        )
                    )
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertInputStateConflictResponse(responses[0], listOf(sharedState[0])) }
                )
            }

            processRequests(
                newRequestBuilder()
                    .setInputStates(
                        listOf(
                            sharedState[2],
                            sharedState[1],
                            sharedState[0]
                        )
                    )
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    {
                        assertInputStateConflictResponse(
                            responses[0],
                            listOf(sharedState[0], sharedState[1])
                        )
                    }
                )
            }
        }
    }

    @Nested
    inner class ReferenceStates {
        @Test
        fun `Attempting to spend an unknown reference state fails`() {
            val referenceStateRef = "${randomSecureHash()}:0"

            processRequests(
                newRequestBuilder()
                    .setReferenceStates(listOf(referenceStateRef))
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertUnknownReferenceStateResponse(responses[0], listOf(referenceStateRef)) }
                )
            }
        }

        @Test
        fun `Single tx, no input states, single ref state is successful`() {
            processRequests(
                newRequestBuilder()
                    .setReferenceStates(generateUnspentStates(1))
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }
        }

        @Test
        fun `Single tx, no input states, single ref state retried in same batch is successful`() {
            val request = newRequestBuilder()
                .setReferenceStates(generateUnspentStates(1))
                .build()

            processRequests(
                request,
                request
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(2) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    { assertStandardSuccessResponse(responses[1], testClock) },
                    // Responses equal (idempotency)
                    { assertEquals(responses[0], responses[1]) }
                )
            }
        }

        @Test
        fun `Single tx, no input states, single ref state retried in different batch is successful`() {
            val request = newRequestBuilder()
                .setReferenceStates(generateUnspentStates(1))
                .build()

            var initialResponse: UniquenessCheckResponseAvro? = null

            processRequests(request).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )

                initialResponse = responses[0]
            }

            processRequests(request).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    // Responses equal (idempotency)
                    { assertEquals(initialResponse, responses[0]) }
                )
            }
        }

        @Test
        fun `Multiple txs, no input states, single shared ref state in same batch is successful`() {
            val sharedState = generateUnspentStates(1)

            processRequests(
                newRequestBuilder()
                    .setReferenceStates(sharedState)
                    .build(),
                newRequestBuilder()
                    .setReferenceStates(sharedState)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(2) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    { assertStandardSuccessResponse(responses[1], testClock) },
                    { assertUniqueCommitTimestamps(responses) }
                )
            }
        }

        @Test
        fun `Multiple txs, no input states, single shared ref state in different batch is successful`() {
            val sharedState = generateUnspentStates(1)

            val allResponses = LinkedList<UniquenessCheckResponseAvro>()

            processRequests(
                newRequestBuilder()
                    .setReferenceStates(sharedState)
                    .build()
            ).also { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }.also { responses ->
                allResponses.add(responses[0])
            }

            processRequests(
                newRequestBuilder()
                    .setReferenceStates(sharedState)
                    .build()
            ).also { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }.also { responses ->
                allResponses.add(responses[0])
            }

            assertUniqueCommitTimestamps(allResponses)
        }

        @Test
        fun `Multiple txs, no input states, multiple distinct ref states in same batch is successful`() {
            processRequests(
                newRequestBuilder()
                    .setReferenceStates(generateUnspentStates(3))
                    .build(),
                newRequestBuilder()
                    .setReferenceStates(generateUnspentStates(1))
                    .build(),
                newRequestBuilder()
                    .setReferenceStates(generateUnspentStates(6))
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(3) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    { assertStandardSuccessResponse(responses[1], testClock) },
                    { assertStandardSuccessResponse(responses[2], testClock) },
                    { assertUniqueCommitTimestamps(responses) }
                )
            }
        }

        @Test
        fun `Multiple txs, no input states, multiple distinct ref states in different batch is successful`() {
            val allResponses = LinkedList<UniquenessCheckResponseAvro>()

            processRequests(
                newRequestBuilder()
                    .setReferenceStates(generateUnspentStates(3))
                    .build()
            ).also { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }.also { responses ->
                allResponses.add(responses[0])
            }

            processRequests(
                newRequestBuilder()
                    .setReferenceStates(generateUnspentStates(1))
                    .build()
            ).also { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }.also { responses ->
                allResponses.add(responses[0])
            }

            processRequests(
                newRequestBuilder()
                    .setReferenceStates(generateUnspentStates(6))
                    .build()
            ).also { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }.also { responses ->
                allResponses.add(responses[0])
            }

            assertUniqueCommitTimestamps(allResponses)
        }

        @Test
        fun `Single tx with single input state, single ref state is successful`() {
            processRequests(
                newRequestBuilder()
                    .setInputStates(generateUnspentStates(1))
                    .setReferenceStates(generateUnspentStates(1))
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }
        }

        @Test
        fun `Single tx with same state used for input and ref state is successful`() {
            val state = generateUnspentStates(1)

            processRequests(
                newRequestBuilder()
                    .setInputStates(state)
                    .setReferenceStates(state)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }
        }

        @Test
        fun `Single tx with already spent ref state fails`() {
            val spentState = generateUnspentStates(1)

            processRequests(
                newRequestBuilder()
                    .setInputStates(spentState)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }

            processRequests(
                newRequestBuilder()
                    .setInputStates(generateUnspentStates(1))
                    .setReferenceStates(spentState)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertReferenceStateConflictResponse(responses[0], spentState) }
                )
            }
        }

        @Test
        fun `Single tx with single ref state replayed after ref state spent is successful`() {
            val state1 = generateUnspentStates(1)
            val replayableRequest = newRequestBuilder()
                .setReferenceStates(state1)
                .build()

            var initialResponse: UniquenessCheckResponseAvro? = null

            processRequests(replayableRequest).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
                initialResponse = responses[0]
            }

            processRequests(
                newRequestBuilder()
                    .setInputStates(state1)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }

            processRequests(replayableRequest).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    // Responses equal (idempotency)
                    { assertEquals(initialResponse, responses[0]) }
                )
            }
        }

        @Test
        fun `Two txs using each others input states as references in same batch passes for tx1, fails for tx2`() {
            val states = List(2) { generateUnspentStates(1) }

            processRequests(
                newRequestBuilder()
                    .setInputStates(states[0])
                    .setReferenceStates(states[1])
                    .build(),
                newRequestBuilder()
                    .setInputStates(states[1])
                    .setReferenceStates(states[0])
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(2) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    { assertReferenceStateConflictResponse(responses[1], states[0]) }
                )
            }
        }

        @Test
        fun `Two txs using each others input states as references in different batch passes for tx1, fails for tx2`() {
            val states = List(2) { generateUnspentStates(1) }

            processRequests(
                newRequestBuilder()
                    .setInputStates(states[0])
                    .setReferenceStates(states[1])
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }

            processRequests(
                newRequestBuilder()
                    .setInputStates(states[1])
                    .setReferenceStates(states[0])
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertReferenceStateConflictResponse(responses[0], states[0]) }
                )
            }
        }
    }

    @Nested
    inner class OutputStates {
        @Test
        fun `Replaying an issuance transaction in same batch is successful`() {
            val issueTxId = randomSecureHash()

            processRequests(
                newRequestBuilder(issueTxId)
                    .setNumOutputStates(3)
                    .build(),
                newRequestBuilder(issueTxId)
                    .setNumOutputStates(3)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(2) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    { assertStandardSuccessResponse(responses[1], testClock) },
                    // Responses equal (idempotency)
                    { assertEquals(responses[0], responses[1]) }
                )
            }
        }

        @Test
        fun `Replaying an issuance transaction in different batch is successful`() {
            val issueTxId = randomSecureHash()
            lateinit var initialResponse: UniquenessCheckResponseAvro

            processRequests(
                newRequestBuilder(issueTxId)
                    .setNumOutputStates(3)
                    .build(),

            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )

                initialResponse = responses[0]
            }

            processRequests(
                newRequestBuilder(issueTxId)
                    .setNumOutputStates(3)
                    .build(),
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    // Responses equal (idempotency)
                    { assertEquals(responses[0], initialResponse) }
                )
            }
        }

        @Test
        fun `Generation and subsequent spend of large number of states is successful`() {
            val issueTxId = randomSecureHash()
            val numStates = Short.MAX_VALUE

            processRequests(
                newRequestBuilder(issueTxId)
                    .setNumOutputStates(numStates.toInt())
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }

            processRequests(
                newRequestBuilder()
                    .setInputStates(List(2048) { "$issueTxId:$it" })
                    .build(),
                newRequestBuilder()
                    .setInputStates(listOf("$issueTxId:${Short.MAX_VALUE}"))
                    .build(),
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(2) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    {
                        assertUnknownInputStateResponse(
                            responses[1], listOf("$issueTxId:${Short.MAX_VALUE}")
                        )
                    }
                )
            }
        }
    }

    @Nested
    inner class TimeWindows {
        @Test
        fun `Tx processed within time window bounds is successful`() {
            processRequests(
                newRequestBuilder()
                    .setTimeWindowLowerBound(currentTime().minusSeconds(10))
                    .setTimeWindowUpperBound(currentTime().plusSeconds(10))
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }
        }

        @Test
        fun `Tx processed within time window bounds and retried outside of bounds is successful`() {
            val request = newRequestBuilder()
                .setTimeWindowLowerBound(currentTime().minusSeconds(10))
                .setTimeWindowUpperBound(currentTime().plusSeconds(10))
                .build()

            var initialResponse: UniquenessCheckResponseAvro? = null

            processRequests(request).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
                initialResponse = responses[0]
            }

            // Move clock past window
            testClock.setTime(testClock.peekTime() + Duration.ofSeconds(100))

            processRequests(request).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    // Responses equal (idempotency)
                    { assertEquals(initialResponse, responses[0]) }
                )
            }
        }

        @Test
        fun `Tx processed before time window lower bound fails`() {
            val lowerBound = currentTime().plusSeconds(10)

            processRequests(
                newRequestBuilder()
                    .setTimeWindowLowerBound(lowerBound)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    {
                        assertTimeWindowOutOfBoundsResponse(
                            responses[0],
                            expectedLowerBound = lowerBound,
                            expectedUpperBound = defaultTimeWindowUpperBound
                        )
                    }
                )
            }
        }

        @Test
        fun `Tx processed before time window lower bound and retried after lower bound fails`() {
            val lowerBound = currentTime().plusSeconds(10)
            val request = newRequestBuilder()
                .setTimeWindowLowerBound(lowerBound)
                .build()
            var initialResponse: UniquenessCheckResponseAvro? = null

            processRequests(request).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    {
                        assertTimeWindowOutOfBoundsResponse(
                            responses[0],
                            expectedLowerBound = lowerBound,
                            expectedUpperBound = defaultTimeWindowUpperBound
                        )
                    }
                )
                initialResponse = responses[0]
            }

            // Tick up clock and retry
            testClock.setTime(testClock.peekTime() + Duration.ofSeconds(100))

            processRequests(request).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    {
                        assertTimeWindowOutOfBoundsResponse(
                            responses[0],
                            expectedLowerBound = lowerBound,
                            expectedUpperBound = defaultTimeWindowUpperBound
                        )
                    },
                    // Responses equal (idempotency)
                    { assertEquals(initialResponse, responses[0]) }
                )
                initialResponse = responses[0]
            }
        }

        @Test
        fun `Tx processed after time window upper bound fails`() {
            val upperBound = currentTime().minusSeconds(10)

            processRequests(
                newRequestBuilder()
                    .setTimeWindowUpperBound(upperBound)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    {
                        assertTimeWindowOutOfBoundsResponse(
                            responses[0], expectedUpperBound = upperBound
                        )
                    }
                )
            }
        }
    }

    @Nested
    inner class MultiTenancy {
        private val bobHoldingIdentity = createTestHoldingIdentity(
            "C=GB, L=London, O=Bob", groupId)
        private val charlieHoldingIdentity = createTestHoldingIdentity(
            "C=GB, L=London, O=Charlie", groupId)
        private val davidHoldingIdentity = createTestHoldingIdentity(
            "C=GB, L=London, O=David", groupId)

        @Test
        fun `Requests for different holding identities are processed independently`() {

            processRequests(
                newRequestBuilder()
                    .setHoldingIdentity(bobHoldingIdentity.toAvro())
                    .setNumOutputStates(1)
                    .build(),
                newRequestBuilder()
                    .setHoldingIdentity(charlieHoldingIdentity.toAvro())
                    .setNumOutputStates(1)
                    .build(),
                newRequestBuilder()
                    .setHoldingIdentity(davidHoldingIdentity.toAvro())
                    .setNumOutputStates(1)
                    .build(),
                newRequestBuilder()
                    .setHoldingIdentity(davidHoldingIdentity.toAvro())
                    .setNumOutputStates(1)
                    .build(),
                newRequestBuilder()
                    .setHoldingIdentity(bobHoldingIdentity.toAvro())
                    .setNumOutputStates(1)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(5) },
                    { assertStandardSuccessResponse(responses[0]) },
                    { assertStandardSuccessResponse(responses[1]) },
                    { assertStandardSuccessResponse(responses[2]) },
                    { assertStandardSuccessResponse(responses[3]) },
                    { assertStandardSuccessResponse(responses[4]) }
                )
            }

            Mockito.verify(backingStore, times(1)).session(eq(bobHoldingIdentity), any())
            Mockito.verify(backingStore, times(1)).session(eq(charlieHoldingIdentity), any())
            Mockito.verify(backingStore, times(1)).session(eq(davidHoldingIdentity), any())
        }

        @Test
        fun `Order of holding id processing is random`() {
            /*
             * There's no easy way to directly interrogate the processing order as this is only
             * accessible via private methods / data structures. However, we can infer the ordering
             * based on response timestamps due to using our auto ticking test clock. As the order
             * is non-deterministic, we simply run the same test a number of times and make sure
             * the order of holding id processing is not the same across all runs. We use enough
             * runs to ensure that probabalistically the results will not be equal by chance.
             * Duplicate probability for 10 runs with 6 combinations = (1/6)^10 ~= 1.65^-8
             */
            val holdingIdsInOrder = List(10) {
                uniquenessChecker.processRequests(
                    listOf(
                        newRequestBuilder()
                            .setHoldingIdentity(bobHoldingIdentity.toAvro())
                            .setNumOutputStates(1)
                            .build(),
                        newRequestBuilder()
                            .setHoldingIdentity(charlieHoldingIdentity.toAvro())
                            .setNumOutputStates(1)
                            .build(),
                        newRequestBuilder()
                            .setHoldingIdentity(davidHoldingIdentity.toAvro())
                            .setNumOutputStates(1)
                            .build()
                    )
                ).entries.sortedBy {
                    (it.value.result as UniquenessCheckResultSuccessAvro).commitTimestamp
                }.map {
                    it.key.holdingIdentity
                }
            }

            // Check at least one run returned a different result from the first
            assertThat(holdingIdsInOrder).anySatisfy { instance ->
                assertThat(instance).isNotEqualTo(holdingIdsInOrder.first())
            }
        }

        @Test
        fun `Spending the same state across different holding identities is accepted`() {
            val issueTxId = randomSecureHash()

            processRequests(
                newRequestBuilder(issueTxId)
                    .setHoldingIdentity(bobHoldingIdentity.toAvro())
                    .setNumOutputStates(1)
                    .build(),
                newRequestBuilder(issueTxId)
                    .setHoldingIdentity(charlieHoldingIdentity.toAvro())
                    .setNumOutputStates(1)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(2) },
                    { assertStandardSuccessResponse(responses[0]) },
                    { assertStandardSuccessResponse(responses[1]) }
                )
            }

            val unspentStateRef = "${issueTxId}:0"

            processRequests(
                newRequestBuilder()
                    .setHoldingIdentity(bobHoldingIdentity.toAvro())
                    .setInputStates(listOf(unspentStateRef))
                    .build(),
                newRequestBuilder()
                    .setHoldingIdentity(charlieHoldingIdentity.toAvro())
                    .setInputStates(listOf(unspentStateRef))
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(2) },
                    { assertStandardSuccessResponse(responses[0]) },
                    { assertStandardSuccessResponse(responses[1]) }
                )
            }
        }

        @Test
        fun `Spending a state that was issued against a different holding id is rejected`() {
            // Generate against default holding id
            val unspentStateRefs = generateUnspentStates(1)

            processRequests(
                newRequestBuilder()
                    .setHoldingIdentity(bobHoldingIdentity.toAvro())
                    .setInputStates(unspentStateRefs)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertUnknownInputStateResponse(responses[0], unspentStateRefs) }
                )
            }
        }
    }

    @Nested
    inner class Miscellaneous {
        @Test
        fun `Empty request list returns no results`() {
            assertEquals(
                emptyMap(),
                uniquenessChecker.processRequests(emptyList())
            )
        }

        @Test
        fun `The same hash code produced by different algorithms are distinct states`() {
            val randomBytes = randomBytes()
            val hash1 = SecureHashImpl("SHA-256", randomBytes)
            val hash2 = SecureHashImpl("SHA-512", randomBytes)
            val hash3 = SecureHashImpl("SHAKE256", randomBytes)

            processRequests(
                newRequestBuilder(hash1)
                    .setNumOutputStates(1)
                    .build(),
                newRequestBuilder(hash2)
                    .setNumOutputStates(1)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(2) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    { assertStandardSuccessResponse(responses[1], testClock) },
                    { assertUniqueCommitTimestamps(responses) }
                )
            }

            processRequests(
                newRequestBuilder()
                    .setInputStates(listOf("$hash1:0"))
                    .build(),
                newRequestBuilder()
                    .setInputStates(listOf("$hash2:0"))
                    .build(),
                newRequestBuilder()
                    .setInputStates(listOf("$hash3:0"))
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(3) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    { assertStandardSuccessResponse(responses[1], testClock) },
                    { assertUnknownInputStateResponse(responses[2], listOf("$hash3:0")) },
                    { assertUniqueCommitTimestamps(listOf(responses[0], responses[1])) }
                )
            }
        }

        @Test
        fun `Issue and subsequent spend of same state in a batch is successful`() {
            val issueTxId = randomSecureHash()

            processRequests(
                newRequestBuilder(issueTxId)
                    .setNumOutputStates(1)
                    .build(),
                newRequestBuilder()
                    .setInputStates(listOf("$issueTxId:0"))
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(2) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    { assertStandardSuccessResponse(responses[1], testClock) },
                    { assertUniqueCommitTimestamps(responses) }
                )
            }
        }

        @Test
        fun `Spend and subsequent issue of same state in a batch fails spend, succeeds issue`() {
            val issueTxId = randomSecureHash()

            processRequests(
                newRequestBuilder()
                    .setInputStates(listOf("$issueTxId:0"))
                    .build(),
                newRequestBuilder(issueTxId)
                    .setNumOutputStates(1)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(2) },
                    { assertUnknownInputStateResponse(responses[0], listOf("$issueTxId:0")) },
                    { assertStandardSuccessResponse(responses[1], testClock) }
                )
            }
        }

        @Test
        fun `Tx failing input state, reference state and time window checks fails on input state check`() {
            // Initial tx to spend an input and reference state
            val states = List(2) { generateUnspentStates(1).single() }

            processRequests(
                newRequestBuilder()
                    .setInputStates(states)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }

            processRequests(
                newRequestBuilder()
                    .setInputStates(listOf(states[0]))
                    .setReferenceStates(listOf(states[1]))
                    .setTimeWindowLowerBound(currentTime().plusSeconds(10))
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertInputStateConflictResponse(responses[0], listOf(states[0])) }
                )
            }
        }

        @Test
        fun `Tx passing input state, failing reference state and time window checks fails on reference state check`() {
            // Initial tx to spend a reference state
            val state = generateUnspentStates(1)

            processRequests(
                newRequestBuilder()
                    .setInputStates(state)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertStandardSuccessResponse(responses[0], testClock) }
                )
            }

            processRequests(
                newRequestBuilder()
                    .setInputStates(generateUnspentStates(1))
                    .setReferenceStates(state)
                    .setTimeWindowLowerBound(currentTime().plusSeconds(10))
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertReferenceStateConflictResponse(responses[0], state) }
                )
            }
        }

        @Test
        fun `Unhandled exception raised in the uniqueness checker returns the appropriate error`() {
            val exceptionThrowingBackingStore = mock<BackingStore>()

            whenever(exceptionThrowingBackingStore.transactionSession(any(), any()))
                .doThrow(UnsupportedOperationException())

            val exceptionThrowingUniquenessChecker = BatchedUniquenessCheckerImpl(
                mock(),
                mock(),
                mock(),
                mock(),
                testClock,
                exceptionThrowingBackingStore)

            exceptionThrowingUniquenessChecker.processRequests(
                listOf(
                    newRequestBuilder()
                        .setNumOutputStates(1)
                        .build()
                )
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(1) },
                    { assertUnhandledExceptionResponse(
                        responses.values.single(),
                        UnsupportedOperationException::class.java.typeName) }
                )
            }
        }

        @Test
        fun `Successful and malformed requests in the same batch return results in correct order`() {
            processRequests(
                newRequestBuilder()
                    .setNumOutputStates(1)
                    .build(),
                newRequestBuilder()
                    .setNumOutputStates(-1)
                    .build()
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(2) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    { assertMalformedRequestResponse(
                        responses[1],
                        "Number of output states cannot be less than 0.") }
                )
            }
        }

        @Suppress("LongMethod")
        @Test
        fun `Complex test scenario with multiple successes and failures in one batch`() {
            val priorSpentStates = List(2) { generateUnspentStates(1).single() }

            uniquenessChecker.processRequests(
                priorSpentStates.map {
                    newRequestBuilder()
                        .setInputStates(listOf(it))
                        .build()
                }
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(2) },
                    { assertStandardSuccessResponse(responses.values.elementAt(0), testClock) },
                    { assertStandardSuccessResponse(responses.values.elementAt(1), testClock) },
                    { assertUniqueCommitTimestamps(responses.values) }
                )
            }

            val retryableSuccessfulRequest = newRequestBuilder()
                .setInputStates(generateUnspentStates(100))
                .setReferenceStates(generateUnspentStates(20))
                .setTimeWindowLowerBound(currentTime())
                .setTimeWindowUpperBound(currentTime().plusSeconds(100))
                .build()

            val retryableFailedRequest = newRequestBuilder()
                .setInputStates(generateUnspentStates(1))
                .setReferenceStates(
                    generateUnspentStates(10) + priorSpentStates[0]
                )
                .build()

            var initialRetryableSuccessfulRequestResponse: UniquenessCheckResponseAvro? = null
            var initialRetryableFailedRequestResponse: UniquenessCheckResponseAvro? = null

            processRequests(retryableSuccessfulRequest, retryableFailedRequest).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(2) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    {
                        assertReferenceStateConflictResponse(
                            responses[1], listOf(priorSpentStates[0])
                        )
                    }
                )
                initialRetryableSuccessfulRequestResponse = responses[0]
                initialRetryableFailedRequestResponse = responses[1]
            }

            val doubleSpendAttemptStates = generateUnspentStates(3)

            val timeWindowUpperBound = currentTime().plusSeconds(1)

            processRequests(
                newRequestBuilder()
                    .setInputStates(
                        listOf(
                            generateUnspentStates(1).single(),
                            doubleSpendAttemptStates[0],
                            doubleSpendAttemptStates[1],
                            doubleSpendAttemptStates[2]
                        )
                    )
                    .setTimeWindowLowerBound(currentTime())
                    .build(),
                newRequestBuilder()
                    .setInputStates(
                        listOf(
                            generateUnspentStates(1).single(),
                            doubleSpendAttemptStates[0],
                            doubleSpendAttemptStates[1]
                        )
                    )
                    .setReferenceStates(
                        listOf(doubleSpendAttemptStates[2])
                    )
                    .build(),
                newRequestBuilder()
                    .setReferenceStates(
                        listOf(doubleSpendAttemptStates[2])
                    )
                    .build(),
                newRequestBuilder()
                    .setInputStates(
                        generateUnspentStates(10) +
                            priorSpentStates[0] +
                            priorSpentStates[1]
                    )
                    .build(),
                retryableFailedRequest,
                retryableSuccessfulRequest,
                newRequestBuilder()
                    .setTimeWindowUpperBound(timeWindowUpperBound)
                    .build(),
                *Array(3) {
                    newRequestBuilder()
                        .setInputStates(generateUnspentStates(3))
                        .setReferenceStates(generateUnspentStates(4))
                        .build()
                }
            ).let { responses ->
                assertAll(
                    { assertThat(responses).hasSize(10) },
                    { assertStandardSuccessResponse(responses[0], testClock) },
                    {
                        assertInputStateConflictResponse(
                            responses[1],
                            listOf(doubleSpendAttemptStates[0], doubleSpendAttemptStates[1])
                        )
                    },
                    {
                        assertReferenceStateConflictResponse(
                            responses[2],
                            listOf(doubleSpendAttemptStates[2])
                        )
                    },
                    {
                        assertInputStateConflictResponse(
                            responses[3],
                            listOf(priorSpentStates[0], priorSpentStates[1])
                        )
                    },
                    { assertEquals(initialRetryableFailedRequestResponse, responses[4]) },
                    { assertEquals(initialRetryableSuccessfulRequestResponse, responses[5]) },
                    {
                        assertTimeWindowOutOfBoundsResponse(
                            responses[6], expectedUpperBound = timeWindowUpperBound
                        )
                    },
                    { assertStandardSuccessResponse(responses[7], testClock) },
                    { assertStandardSuccessResponse(responses[8], testClock) },
                    { assertStandardSuccessResponse(responses[9], testClock) },
                    {
                        assertUniqueCommitTimestamps(
                            responses.filter {
                                it.result is UniquenessCheckResultSuccessAvro
                            }
                        )
                    }
                )
            }
        }
    }
}
