package com.pierbezuhoff.clonium.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

interface Game {
    /** Initial [Game] parameters, [Game] can be constructed from it */
    data class State(
        val board: SimpleBoard,
        /** All the rest of [board]'s [Player]s are [HumanPlayer]s */
        val bots: Map<PlayerId, PlayerTactic.Bot>,
        /** `null` means shuffle before starting game */
        val order: List<PlayerId>? = null
    ) : Serializable

    val board: Board
    val players: Map<PlayerId, Player>
    val order: List<Player>
    val lives: Map<Player, Boolean>
    val currentPlayer: Player

    /** <= 1 alive player */
    fun isEnd(): Boolean

    /** Possible turns ([Pos]s) of [currentPlayer] */
    fun possibleTurns(): Set<Pos>

    /** [Player] to (# of [Chip]s, sum of [Chip] [Level]s) or `null` if dead */
    fun stat(): Map<Player, Pair<Int, Int>?>

    fun humanTurn(pos: Pos): Sequence<Transition>

    fun CoroutineScope.botTurnAsync(): Deferred<Sequence<Transition>>
}

class SimpleGame(
    override val board: EvolvingBoard,
    bots: Set<Bot>,
    initialOrder: List<PlayerId>? = null
) : Game {
    override val players: Map<PlayerId, Player>
    override val order: List<Player>
    override val lives: MutableMap<Player, Boolean>
    @Suppress("RedundantModalityModifier") // or else "error: property must be initialized or be abstract"
    final override var currentPlayer: Player

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
        require(order.isNotEmpty()) { "SimpleGame should have players" }
        lives = order.associateWith { board.possOf(it.playerId).isNotEmpty() }.toMutableMap()
        require(lives.values.any()) { "Someone should be alive" }
        currentPlayer = order.first { isAlive(it) }
    }

    constructor(gameState: Game.State) : this(
        PrimitiveBoard(gameState.board),
        gameState.bots
            .map { (playerId, tactic) -> tactic.toPlayer(playerId) }
            .toSet(),
        gameState.order
    )

    private fun isAlive(player: Player): Boolean =
        lives.getValue(player)

    private fun nextPlayer(): Player {
        val ix = order.indexOf(currentPlayer)
        return (order.drop(ix + 1) + order).first { isAlive(it) }
    }

    override fun possibleTurns(): Set<Pos> =
        board.possOf(currentPlayer.playerId)

    /** (# of [Chip]s, sum of [Chip] [Level]s) or `null` if dead */
    private fun statOf(player: Player): Pair<Int, Int>? {
        return if (!isAlive(player))
            null
        else {
            val ownedChips = board.asPosMap().values
                .filterNotNull()
                .filter { it.playerId == player.playerId }
            Pair(ownedChips.size, ownedChips.sumBy { it.level.ordinal })
        }
    }

    /** [Player] to (# of [Chip]s, sum of [Chip] [Level]s) or `null` if dead */
    override fun stat(): Map<Player, Pair<Int, Int>?> =
        order.associateWith { statOf(it) }

    override fun isEnd(): Boolean =
        lives.values.filter { it }.size <= 1

    private fun makeTurn(turn: Pos): Sequence<Transition> {
        require(turn in possibleTurns())
        val transitions = board.incAnimated(turn)
        lives.clear()
        order.associateWithTo(lives) { board.possOf(it.playerId).isNotEmpty() }
        currentPlayer = nextPlayer()
        return transitions
    }

    override fun humanTurn(pos: Pos): Sequence<Transition> {
        require(currentPlayer is HumanPlayer)
        return makeTurn(pos)
    }

    override fun CoroutineScope.botTurnAsync(): Deferred<Sequence<Transition>> {
        require(currentPlayer is Bot)
        return async(Dispatchers.Default) {
            val turn =
                with(currentPlayer as Bot) {
                    makeTurnAsync(board, order.map { it.playerId })
                }.await()
            return@async makeTurn(turn)
        }
    }

    companion object {
        fun example(): SimpleGame {
            val board = BoardFactory.spawn4players(EmptyBoardFactory.TOWER)
            val bots: Set<Bot> =
                setOf(
                    RandomPickerBot(PlayerId(0)),
                    RandomPickerBot(PlayerId(1)),
                    RandomPickerBot(PlayerId(2)),
                    RandomPickerBot(PlayerId(3))
//                LevelMaximizerBot(PlayerId(2), depth = 1),
//                ChipCountMaximizerBot(PlayerId(3), depth = 1)
                )
            return SimpleGame(PrimitiveBoard(board), bots)
        }
    }
}
