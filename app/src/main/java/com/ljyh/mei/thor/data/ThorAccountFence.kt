package com.ljyh.mei.thor.data

import com.ljyh.mei.data.repository.LibraryAccount
import com.ljyh.mei.thor.model.ThorAccountState
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Opaque accepted-operation identity. It never exposes or prints account credentials. */
class ThorAccountTicket internal constructor(
    internal val account: LibraryAccount?,
    internal val generation: Long,
) {
    override fun toString(): String = "ThorAccountTicket"
}

/** Account identity and generation change atomically, independently of asynchronous UI projection. */
internal class ThorAccountFence(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val current = AtomicReference(ThorAccountTicket(null, 0))
    private val mutationMutex = Mutex()
    val account get() = current.get().account
    val generation get() = current.get().generation

    fun changeAccount(account: LibraryAccount?): ThorAccountTicket =
        current.updateAndGet { previous -> ThorAccountTicket(account, previous.generation + 1) }

    fun capture(publicAccount: ThorAccountState): ThorAccountTicket? = current.get().takeIf {
        it.generation == publicAccount.generation && it.account?.userId == publicAccount.userId
    }

    fun isCurrent(ticket: ThorAccountTicket): Boolean = current.get() === ticket
    fun checkCurrent(ticket: ThorAccountTicket) = check(isCurrent(ticket)) { "账号已变化，请重试" }

    suspend fun ticketForCall(): ThorAccountTicket = currentCoroutineContext()[ExpectedThorAccount]
        ?.takeIf { it.owner === this }?.ticket ?: current.get()

    suspend fun <T> withTicket(ticket: ThorAccountTicket, action: suspend () -> T): T =
        withContext(ExpectedThorAccount(this, ticket)) {
            checkCurrent(ticket)
            action().also { checkCurrent(ticket) }
        }

    suspend fun <T> mutate(action: suspend (LibraryAccount?) -> T): T {
        val expected = currentCoroutineContext()[ExpectedThorAccount]?.takeIf { it.owner === this }
            ?: ExpectedThorAccount(this, current.get())
        return withContext(dispatcher + expected) {
            mutationMutex.withLock {
                checkCurrent(expected.ticket)
                expected.guard()
                checkCurrent(expected.ticket)
                action(expected.ticket.account)
            }
        }
    }

    suspend fun <T> freezeUndo(guard: suspend () -> Unit, action: suspend () -> T): suspend () -> T {
        val ticket = ticketForCall()
        return {
            withContext(ExpectedThorAccount(this, ticket, guard)) {
                checkCurrent(ticket)
                action().also { checkCurrent(ticket) }
            }
        }
    }
}

private class ExpectedThorAccount(
    val owner: ThorAccountFence,
    val ticket: ThorAccountTicket,
    val guard: suspend () -> Unit = {},
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExpectedThorAccount>
}
