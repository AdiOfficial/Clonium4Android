package com.pierbezuhoff.clonium.domain

import com.pierbezuhoff.clonium.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import kotlin.math.min

class AsyncGame(
    override val board: EvolvingBoard,
    bots: Set<BotPlayer>,
    initialOrder: List<PlayerId>? = null,
    override val coroutineScope: CoroutineScope
) : Any()
    , Game
    , WithLog by AndroidLogOf<AsyncGame>()
{
    override val players: Map<PlayerId, Player>
    override val order: List<Player>
    override val lives: MutableMap<Player, Boolean>
    @Suppress("RedundantModalityModifier") // or else "error: property must be initialized or be abstract"
    final override var currentPlayer: Player
    private val linkedTurns: LinkedTurns
    override var lastTurn: Pos? = null
        private set

    init {
        initialOrder?.let {
            require(board.players() == initialOrder.toSet()) { "order is incomplete: $initialOrder instead of ${board.players()}" }
        }
        val playerIds = initialOrder ?: board.players().shuffled().toList()
        require(bots.map { it.playerId }.all { it in playerIds }) { "Not all bot ids are on the board" }
        val botIds = bots.map { it.playerId }
        val botMap = bots.associateBy { it.playerId }
        val humanMap = (playerIds - botIds).associateWith { HumanPlayer(it) }
        players = botMap + humanMap
        order = playerIds.map { players.getValue(it) }
        require(order.isNotEmpty()) { "AsyncGame should have players" }
        lives = order.associateWith { board.possOf(it.playerId).isNotEmpty() }.toMutableMap()
        require(lives.values.any()) { "Someone should be alive" }
        currentPlayer = order.first { isAlive(it) }
        val threadFactory = ThreadFactory { Thread(it).apply { priority = Thread.MIN_PRIORITY } }
//        val nThreads = 4 //Runtime.getRuntime().availableProcessors()
//        val threadPool = ForkJoinPool(nThreads, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true)
//        val threadPool = Executors.newFixedThreadPool(nThreads, threadFactory)
        // NOTE: there are different pools, though I hardly see any difference
        val threadPool = Executors.newCachedThreadPool(threadFactory)
        val constrainedCoroutineScope = coroutineScope + threadPool.asCoroutineDispatcher()
        linkedTurns = LinkedTurns(board, order.map { it.playerId }, players, constrainedCoroutineScope)
    }

    constructor(gameState: Game.State, coroutineScope: CoroutineScope) : this(
        PrimitiveBoard.Factory.of(gameState.board),
        gameState.bots
            .map { (playerId, tactic) -> tactic.toPlayer(playerId) }
            .toSet(),
        gameState.order,
        coroutineScope
    )

    private fun makeTurn(turn: Pos, trans: Trans): Sequence<Transition> {
        require(turn in possibleTurns()) { "turn $turn of $currentPlayer is not possible on board $board" }
        lastTurn = turn
        val transitions = board.incAnimated(turn)
        // require(board == trans.board) { "game board = $board,\ntrans board = ${trans.board}" } // only for testing, too time-consuming
        lives.clear()
        order.associateWithTo(lives) { board.possOf(it.playerId).isNotEmpty() }
        require(trans.order.size == nPlayers)
        currentPlayer = nextPlayer()
        require(currentPlayer.playerId == trans.order.first())
        return transitions
    }

    override fun humanTurn(pos: Pos): Sequence<Transition> {
        require(currentPlayer is HumanPlayer)
        val trans = linkedTurns.givenHumanTurn(pos)
        return makeTurn(pos, trans)
    }

    override suspend fun botTurn(): Pair<Pos, Sequence<Transition>> {
        require(currentPlayer is BotPlayer)
        val (turn, trans) = linkedTurns.requestBotTurnAsync().await()
        return turn to makeTurn(turn, trans)
    }

    object Factory : Game.Factory {
        override fun of(gameState: Game.State, coroutineScope: CoroutineScope): Game =
            AsyncGame(gameState, coroutineScope)
    }
}

/**
 *                                            (check selfNext.trans.order.first())
 * discoverUnknowns ---> Next(Unknown).scheduleTurn => * Next(Unknown -> OneOf(Unknown...)) ->|                          (check computing is null)
 *  (use unknowns)  \--> ...                           * ------------------------------------> Next(Unknown).scheduledComputation => * Next(Unknown) to scheduledComputings ->|
 * ^                 \-> ...                                                                  (use computing & scheduledComputings)  * Next(Unknown) to computing ----------->|:~>.
 * |                                                                                                                                                                              |
 * |                                                                                                                                                                              |
 * |                                 .~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~> (async) |
 * |                                 ^ (async)                                                                                                                                   \|
 * | (may try to interrupt           |                              (check scheduledComputings is empty)         (computing = null; add unknown after computed to unknowns)       |
 * | parent branch => synchronize)   .~~~~~:|<- scheduledComputing to computing * <=========== runNextOrDiscover <------------------ onComputed(Computed) <~~~~~~~~~~~~~~~~~~~~~~~.
 * .<-------------------------------------------------------------------------- *    (use computing & scheduledComputings)      (use computing & unknowns)  (<= 1 computing at time)
 * ^                                                                                                    ^                                   \ ^
 *                                                                                                      |                                   //
 *                                                                                                      | (secondary branch => synchronize)//
 *                                                                                                      |                                 //
 *                              (async)    (clear all)    (structural recursion)                        |                                //
 *  givenHumanTurn -> setFocus  ~~~~~~~~> rescheduleAll \---> Next.reschedule /-------------------------.                               //
 *                    and return                         \--> ...            /                                                         //
 *                                                        \-> ...           /                                                         //
 *                                    (use computing, scheduledComputings, unknowns)                                                 //
 *                                                                                                                                  //
 *        (focus is Computed | Computing)                                                                                          //
 *  requestBotTurnAsync -> tryRequestBotTurnOrAsync => * setFocus -> return                                                       //
 *                                                     * ~~~~~~> wait bot -> setFocus -> return                                  //
 *                                                       (async)      \ ^                                                       //
 *                                                                     \\<-----------------------------------------------------//
 *                                                                      \------------------------------------------------------/
 *                                                                                            (implicit)
 */
private class LinkedTurns(
    board: EvolvingBoard,
    order: List<PlayerId>,
    private val players: Map<PlayerId, Player>,
    private val coroutineScope: CoroutineScope
) : Any()
    , WithLog by AndroidLogOf<LinkedTurns>(Logger.Level.INFO)
{
    private var computing: NextAs<Link.FutureTurn.Bot.Computing>? = null
    private val scheduledComputings: AbstractQueue<NextAs<Link.FutureTurn.Bot.ScheduledComputing>> =
        PriorityBlockingQueue(1, NextAsDepthComparator())
    private val unknowns: AbstractQueue<NextAs<Link.Unknown>> =
        PriorityBlockingQueue(10, NextAsDepthComparator())
    private var start: Link.Start = Link.Start(nextUnknown(Trans(board, order), depth = 1))
    private val focus: Link get() = start.next.link
    private var nOfTraversedTurns = 0

    private val lock = Mutex() // NOTE: guaranteed to be fair: FIFO
    private val humanAfterEffect = Mutex()

    init {
        require(order.isNotEmpty())
        if (order.size == 1) {
            unknowns.clear()
            start.next.link = Link.End
        } else {
            coroutineScope.launch(Dispatchers.Default) {
                synchronized {
                    discoverUnknowns()
                }
            }
        }
    }

    private fun discoverUnknowns() {
        while (
            unknowns.isNotEmpty() &&
            (unknowns.size < SOFT_MAX_N_OF_UKNOWNS || players.getValue(unknowns.element().selfNext.trans.order.first()) is BotPlayer) &&
            (computeDepth(depth = 0)!! <= SOFT_MIN_DEPTH || computeWidth() < SOFT_MAX_WIDTH)
        ) {
            val nextAs = unknowns.remove()
            nextAs.selfNext.scheduleTurn(nextAs.link.depth)
        }
    }

    private fun Next.scheduleTurn(depth: Int) {
        val board = trans.board
        val order = trans.order
        require(order.isNotEmpty())
        val playerId = order.first()
        when (val player = players.getValue(playerId)) {
            is HumanPlayer -> {
                val possibleTurns = board.possOf(player.playerId)
                link = Link.FutureTurn.Human.OneOf(
                    player.playerId,
                    // NOTE: introducing chained turns (via the same unknown) caused
                    //  board divergence error (in AsyncGame.makeTurn) after rescheduleAll
                    possibleTurns.associateWith { turn ->
                        val nextBoard = board.copy()
                        nextBoard.inc(turn)
                        val nextOrder = nextBoard.shiftOrder(order)
                        val trans = Trans(nextBoard, nextOrder)
                        return@associateWith if (nextOrder.size <= 1)
                            Next(trans, Link.End)
                        else
                            nextUnknown(trans, depth + 1)
                    }
                )
            }
            is BotPlayer ->
                scheduleComputation(player, board, order, depth = depth)
            else -> impossibleCaseOf(player)
        }
    }

    private fun nextUnknown(trans: Trans, depth: Int): Next {
        val unknown = Link.Unknown(depth)
        val nextAs = NextAs(trans, unknown)
        unknowns.add(nextAs)
        return nextAs.selfNext
    }

    private fun Next.scheduleComputation(bot: BotPlayer, board: EvolvingBoard, order: List<PlayerId>, depth: Int) {
        require(board.possOf(bot.playerId).isNotEmpty())
        val computation = Computation(bot, board, order, depth, Id.generate())
        if (computing == null) {
            startComputing(computation)
        } else {
            val scheduledComputing = Link.FutureTurn.Bot.ScheduledComputing(bot.playerId, depth, computation)
            scheduledComputings.add(NextAs(this, scheduledComputing))
        }
    }

    private fun Next.startComputing(computation: Computation) {
        require(computing == null)
        val newComputing = Link.FutureTurn.Bot.Computing(
            computation.bot.playerId, computation.depth, computation, runComputationAsync(computation)
        )
        computing = NextAs(this, newLink = newComputing)
    }

    private fun runComputationAsync(computation: Computation): Deferred<Link.FutureTurn.Bot.Computed> =
        coroutineScope.async {
            computation.run { onComputed(it) }
        }

    private fun onComputed(computed: Link.FutureTurn.Bot.Computed) {
        coroutineScope.launch {
            synchronized {
                val computing = computing
                when {
                    computing == null -> Unit
                    computed.id != computing.link.computation.id -> Unit
                    else -> {
                        computing.selfNext.link = computed
                        this@LinkedTurns.computing = null
                        // add external unknown (from Computation.run)
                        when (val nextLink = computed.next.link) {
                            is Link.Unknown ->
                                unknowns.add(NextAs(computed.next, nextLink))
                            is Link.End -> Unit
                            else -> impossibleCaseOf(nextLink)
                        }
                        runNextOrDiscover()
                    }
                }
            }
        }
    }

    private fun runNextOrDiscover() {
        val shouldDiscoverUnknowns = !runNext()
        if (shouldDiscoverUnknowns) {
            discoverUnknowns()
        }
    }

    /** Return `false` if there are no [scheduledComputings] available and [discoverUnknowns] should be started,
     * `true` otherwise */
    private fun runNext(): Boolean {
        if (computing == null) {
            val maybeScheduled = scheduledComputings.poll()
            if (maybeScheduled == null) {
                return false // no scheduled => to be discovered
            } else {
                val scheduledNextAs: NextAs<Link.FutureTurn.Bot.ScheduledComputing> = maybeScheduled
                scheduledNextAs.selfNext.startComputing(scheduledNextAs.link.computation)
            }
        }
        return true
    }

    fun givenHumanTurn(turn: Pos): Trans {
        when (val focus = focus) {
            is Link.FutureTurn.Human.OneOf -> {
                require(turn in focus.nexts.keys)
                runBlocking {
                    humanAfterEffect.lock()
                }
                val next = focus.nexts.getValue(turn)
                coroutineScope.launch(Dispatchers.Default) {
                    synchronized {
                        setFocus(next)
                        rescheduleAll()
                    }
                    humanAfterEffect.unlock()
                }
                return next.trans
            }
            is Link.End -> {
                val nextBoard = start.next.trans.board.copy()
                nextBoard.inc(turn)
                val nextOrder = nextBoard.shiftOrder(start.next.trans.order)
                val trans = Trans(nextBoard, nextOrder)
                setFocus(Next(trans, Link.End))
                return trans
            }
            else -> impossibleCaseOf(focus) { logIState() }
        }
    }

    private fun setFocus(next: Next) {
        start = Link.Start(next)
        nOfTraversedTurns ++
    }

    private fun rescheduleAll() {
        computing = null
        scheduledComputings.clear()
        unknowns.clear()
        start.next.reschedule()
        runNextOrDiscover()
    }

    private fun Next.reschedule() {
        when (val link = link) {
            is Link.Unknown -> unknowns.add(NextAs(this, link))
            is Link.FutureTurn.Bot.ScheduledComputing -> scheduledComputings.add(NextAs(this, link))
            is Link.FutureTurn.Bot.Computing -> computing = NextAs(this, link)
            is Link.FutureTurn.Bot.Computed -> link.next.reschedule()
            is Link.FutureTurn.Human.OneOf -> for ((_, next) in link.nexts) next.reschedule()
        }
    }

    fun requestBotTurnAsync(): Deferred<Pair<Pos, Trans>> = coroutineScope.async {
        syncronizedAfterHumanTurn {
            synchronized {
                when (val focus = focus as Link.FutureTurn.Bot) {
                    is Link.FutureTurn.Bot.Computed -> {
                        setFocus(focus.next)
                        return@synchronized focus.pos to focus.next.trans
                    }
                    is Link.FutureTurn.Bot.Computing -> {
                        val (elapsedPretty, computed) = measureElapsedTimePretty {
                            focus.deferred.await()
                        }
                        log w "Slow bot ${players.getValue(computed.playerId)}: $elapsedPretty\n"
                        setFocus(computed.next)
                        return@synchronized computed.pos to computed.next.trans
                    }
                    else -> {
                        logIState()
                        // ImpossibleCaseOf(focus).printStackTrace()
                        // handle it
                        impossibleCaseOf(focus)
                    }
                }
            }
        }
    }

    private fun logIState() {
        log i (
            """focus = $focus,
            |computing = $computing
            |scheduledComputings = ${scheduledComputings.toPrettyString()}
            |unknowns = ${unknowns.toPrettyString()}""".trimMargin()
        )
    }

    /** Depth of [link] + [depth], `null` means infinite: all possibilities are computed */
    private fun computeDepth(link: Link = focus, depth: Int = nOfTraversedTurns): Int? =
        when (link) {
            is Link.End -> null
            is Link.TemporalTerminal -> depth
            is Link.FutureTurn.Bot.Computed -> computeDepth(link.next.link, depth + 1)
            is Link.FutureTurn.Human.OneOf -> link.nexts.values
                .map { next -> computeDepth(next.link, depth + 1) }
                .fold(null as Int?) { d0: Int?, d1: Int? ->
                    if (d0 == null) d1 else if (d1 == null) d0 else min(d0, d1)
                }
            else -> impossibleCaseOf(link)
        }

    private fun computeWidth(root: Link = focus): Int =
        when (root) {
            is Link.FutureTurn.Bot.Computed -> computeWidth(root.next.link)
            is Link.FutureTurn.Human.OneOf -> root.nexts.values.sumBy { computeWidth(it.link) }
            is Link.Terminal -> 1
            else -> impossibleCaseOf(root)
        }

    private class NextAsDepthComparator<L> : Comparator<NextAs<L>> where L : Link.WithDepth, L : Link {
        override fun compare(o1: NextAs<L>, o2: NextAs<L>): Int =
            o1.link.depth.compareTo(o2.link.depth)
    }

    private suspend inline fun <R> synchronized(block: () -> R): R =
        lock.withLock(null, block)

    private suspend inline fun <R> syncronizedAfterHumanTurn(block: () -> R): R =
        humanAfterEffect.withLock(null, block)

    companion object {
        private const val SOFT_MAX_WIDTH = 100
        private const val SOFT_MIN_DEPTH = 5
        private const val SOFT_MAX_N_OF_UKNOWNS = 500
    }
}

private sealed class Link {
    interface Transient // has next or nexts
    interface Terminal // has no next
    interface TemporalTerminal : Terminal // may become Transient in future
    interface WithDepth { val depth: Int }

    class Start(
        val next: Next
    ) : Link(), Transient {
        override fun toString(indent: Int) =
            indentOf(indent) + "Start:\n" + next.toString(indent + 1)
    }

    object End : Link(), Terminal {
        override fun toString() =
            "End"
    }

    class Unknown(
        override val depth: Int
    ) : Link(), TemporalTerminal, WithDepth {
        override fun toString() =
            "Unknown(depth = $depth)"
    }

    sealed class FutureTurn(
        val playerId: PlayerId
    ) : Link() {

        sealed class Human(
            playerId: PlayerId
        ) : FutureTurn(playerId) {
            class OneOf(
                playerId: PlayerId,
                val nexts: Map<Pos, Next>
            ) : Human(playerId), Transient {
                override fun toString(indent: Int) =
                    indentOf(indent) + "Human.OneOf($playerId):" + nexts.entries.joinToString(
                        prefix = "\n${indentOf(indent + 1)}[\n",
                        separator = ",\n",
                        postfix = "\n${indentOf(indent + 1)}]"
                    ) { (pos, next) -> indentOf(indent + 1) + "$pos ->\n${next.toString(indent + 2)}" }
            }
        }

        sealed class Bot(
            playerId: PlayerId
        ) : FutureTurn(playerId) {
            class Computed(
                playerId: PlayerId,
                val pos: Pos,
                val next: Next,
                val id: Id
            ) : Bot(playerId), Transient {
                override fun toString(indent: Int) =
                    indentOf(indent) + "Bot.Computed($playerId, $pos, $id):\n" + next.toString(indent + 1)
            }
            class Computing(
                playerId: PlayerId,
                override val depth: Int,
                val computation: Computation,
                val deferred: Deferred<Computed>
            ) : Bot(playerId), TemporalTerminal, WithDepth {
                override fun toString(): String =
                    "Bot.Computing($playerId, depth = $depth, $computation)"
            }
            class ScheduledComputing(
                playerId: PlayerId,
                override val depth: Int,
                val computation: Computation
            ) : Bot(playerId), TemporalTerminal, WithDepth {
                override fun toString(): String =
                    "Bot.ScheduledComputing($playerId, depth = $depth, $computation)"
            }
        }
    }

    internal open fun toString(indent: Int): String =
        indentOf(indent) + toString()
    internal fun indentOf(indent: Int): String =
        "  ".repeat(indent)
    override fun toString(): String =
        toString(0)
}

private data class Next(
    val trans: Trans,
    var link: Link
) {
    fun toString(indent: Int): String =
        link.toString(indent)

    override fun toString(): String =
        toString(0)
}

private class NextAs<out L : Link>(
    val selfNext: Next,
    newLink: L
) {
    /** Typed selfNext.link */
    val link: L = newLink
    init {
        selfNext.link = link
    }

    constructor(trans: Trans, link: L) : this(Next(trans, link), link)

    override fun equals(other: Any?) =
        other is NextAs<*> && selfNext == other.selfNext
    override fun hashCode() =
        selfNext.hashCode()
    override fun toString() =
        selfNext.toString()
}

private data class Trans(
    val board: EvolvingBoard,
    val order: List<PlayerId>
)

private data class Computation(
    val bot: BotPlayer,
    val board: EvolvingBoard,
    val order: List<PlayerId>,
    val depth: Int,
    val id: Id
) {
    suspend inline fun run(
        crossinline andThen: (Link.FutureTurn.Bot.Computed) -> Unit = {}
    ): Link.FutureTurn.Bot.Computed =
        withContext(Dispatchers.Default) {
            val turn = bot.makeTurn(board, order)
            val endBoard = board.copy()
            endBoard.inc(turn)
            val endOrder = endBoard.shiftOrder(order)
            val trans = Trans(endBoard, endOrder)
            val nextLink: Link =
                if (endOrder.size <= 1) Link.End
                else Link.Unknown(depth + 1) // will be added to LinkedTurns.unknowns in LinkedTurns.onComputed
            val computed = Link.FutureTurn.Bot.Computed(
                bot.playerId, turn, Next(trans, nextLink), id
            )
            andThen(computed)
            computed
        }
}

private class Id private constructor(val id: Int) {
    override fun equals(other: Any?): Boolean =
        other is Id && id == other.id
    override fun hashCode(): Int =
        id.hashCode()
    override fun toString(): String =
        "Id($id)"
    companion object {
        private var counter = 0
        fun generate(): Id =
            Id(counter++)
    }
}

private fun <E> Iterable<E>.toPrettyString(): String =
    joinToString(prefix = "[\n", separator = ",\n", postfix = "\n]")

