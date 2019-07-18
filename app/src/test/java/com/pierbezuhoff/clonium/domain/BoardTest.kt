package com.pierbezuhoff.clonium.domain

import com.pierbezuhoff.clonium.domain.Explosion.EndState.*
import io.kotlintest.inspectors.forAll
import io.kotlintest.matchers.collections.shouldContainExactly
import io.kotlintest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.matchers.maps.shouldContainExactly
import io.kotlintest.matchers.sequences.shouldContainExactly
import io.kotlintest.matchers.sequences.shouldHaveAtLeastSize
import io.kotlintest.matchers.sequences.shouldHaveSize
import io.kotlintest.matchers.withClue
import io.kotlintest.properties.Gen
import io.kotlintest.properties.assertAll
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.FreeSpec
import kotlinx.coroutines.plus
import kotlin.math.exp

class BoardTest : FreeSpec() {
    init {
        "interface EmptyBoard" - {
            "copy" - {
                "EmptyBoard" {
                    SimpleEmptyBoardGenerator().assertAll(iterations = 1_000) { board: SimpleEmptyBoard ->
                        val initialPoss = board.posSet.toSet()
                        val copied = board.copy()
                        board.posSet.add(Pos(board.width - 1, board.height - 1))
                        board.posSet.remove(board.posSet.first())
                        copied.width shouldBe board.width
                        copied.height shouldBe board.height
                        copied.asPosSet() shouldContainExactly initialPoss
                    }
                }
                "SimpleBoard" {
                    SimpleBoardGenerator().assertAll(iterations = 1_000) { board: SimpleBoard ->
                        val initialPosMap = board.posMap.toMap()
                        val copied = board.copy()
                        board.posMap[Pos(board.width - 1, board.height - 1)] = Chip(PlayerId(0), Level(2))
                        board.posMap.remove(board.posMap.keys.first())
                        board.posMap[Pos(0, 0)] = Chip(PlayerId(1), Level(2))
                        copied.width shouldBe board.width
                        copied.height shouldBe board.height
                        copied.asPosMap() shouldContainExactly initialPosMap
                    }
                }
                "PrimitiveBoard" {
                    PopulatedPrimitiveBoardGenerator().assertAll(iterations = 1_000) { board: PrimitiveBoard ->
                        val initialPosMap = board.asPosMap()
                        val copied = board.copy()
                        board.inc(board.possOf(board.players().first()).last())
                        copied.width shouldBe board.width
                        copied.height shouldBe board.height
                        copied.asPosMap() shouldContainExactly initialPosMap
                    }
                }
            }
            "asPosSet & hasCell" - {
                "PrimitiveBoard" {
                    PrimitiveBoardGenerator().assertAll(iterations = 10_000) { board: PrimitiveBoard ->
                        PosGenerator(board).assertAll(iterations = 10) { pos: Pos ->
                            board.hasCell(pos) shouldBe (pos in board.asPosSet())
                        }
                    }
                }
            }
        }

        "interface Board" - {
            "PrimitiveBoard" - {
                "asPosSet & asPosMap & chipAt & hasCell & hasChip" {
                    PrimitiveBoardGenerator().assertAll(iterations = 10_000) { board: PrimitiveBoard ->
                        board.asPosSet() shouldContainExactlyInAnyOrder board.asPosMap().keys
                        PosGenerator(board).assertAll(iterations = 10) { pos: Pos ->
                            (board.chipAt(pos) != null) shouldBe board.hasChip(pos)
                            board.chipAt(pos) shouldBe board.asPosMap()[pos]
                        }
                    }
                }
                "chipAt & playerAt & levelAt" {
                    PrimitiveBoardGenerator().assertAll(iterations = 10_000) { board: PrimitiveBoard ->
                        PosGenerator(board).assertAll(iterations = 10) { pos: Pos ->
                            board.chipAt(pos)?.level shouldBe board.levelAt(pos)
                            board.chipAt(pos)?.playerId shouldBe board.playerAt(pos)
                        }
                    }
                }
                "asPosMap & players & isAlive" {
                    PrimitiveBoardGenerator().assertAll(iterations = 10_000) { board: PrimitiveBoard ->
                        board.players() shouldContainExactlyInAnyOrder
                                board.asPosMap()
                                    .values.mapNotNull { it?.playerId }
                                    .toSet()
                        board.players().forAll { board.isAlive(it) shouldBe true }
                    }
                }
                "asPosMap & possOf & players & isAlive" {
                    PrimitiveBoardGenerator().assertAll(iterations = 10_000) { board: PrimitiveBoard ->
                        board.players().forAll { playerId ->
                            board.possOf(playerId) shouldContainExactlyInAnyOrder
                                    board.asPosMap()
                                        .filterValues { it?.playerId == playerId }
                                        .keys
                            board.isAlive(playerId) shouldBe board.possOf(playerId).isNotEmpty()
                        }
                        board.asPosMap().filterValues { it != null }.keys shouldContainExactlyInAnyOrder
                                board.players().flatMap { board.possOf(it) }
                    }
                }
            }
        }

        "interface EvolvingBoard" - {
            "PrimitiveBoard" - {
                "inc & incAnimated" {
                    PrimitiveBoardGenerator().assertAll { board: PrimitiveBoard ->
                        board.asPosMap()
                            .filterValues { it != null }
                            .keys
                            .forAll {
                                val board1 = board.copy().apply { inc(it) }
                                val board2 = board.copy().apply { incAnimated(it) }
                                board1.asPosMap() shouldContainExactly board2.asPosMap()
                            }
                        val initialBoard = board.copy()
                        board.asPosMap()
                            .filterValues { it == null }
                            .keys
                            .forAll {
                                shouldThrow<EvolvingBoard.InvalidTurn> { board.inc(it) }
                                board.asPosMap() shouldContainExactly initialBoard.asPosMap()
                                shouldThrow<EvolvingBoard.InvalidTurn> { board.incAnimated(it) }
                                board.asPosMap() shouldContainExactly initialBoard.asPosMap()
                            }
                    }
                }
                "incAnimated" - {
                    "single chip 3 level" {
                        SimpleEmptyBoardGenerator().assertAll { emptyBoard: EmptyBoard ->
                            // PrimitiveBoard with single chip 3 level
                            emptyBoard.asPosSet().forAll { pos: Pos ->
                                val playerId = PlayerId(1)
                                val _single3: Board = SimpleBoard(emptyBoard).apply {
                                    addChipAt(Chip(playerId, Level(3)), pos)
                                }
                                val single3 = PrimitiveBoard(_single3)
                                val transitions = single3.incAnimated(pos)
                                single3.chipAt(pos) shouldBe null
                                transitions shouldHaveSize 1
                                val transition = transitions.first()
                                transition.explosions shouldHaveSize 1
                                transition.interimBoard.asPosMap() shouldContainExactly SimpleBoard(emptyBoard).asPosMap()
                                transition.endBoard.asPosMap() shouldContainExactly single3.asPosMap()
                                val explosion = transition.explosions.first()
                                explosion.center shouldBe pos
                                explosion.playerId shouldBe playerId
                                val newChip = Chip(playerId, Level(1))
                                val sides = with(explosion) {
                                    mapOf(
                                        right to Pos(pos.x + 1, pos.y),
                                        left to Pos(pos.x - 1, pos.y),
                                        up to Pos(pos.x, pos.y - 1),
                                        down to Pos(pos.x, pos.y + 1)
                                    )
                                }
                                for ((side, sidePos) in sides)
                                    if (emptyBoard.hasCell(sidePos)) {
                                        single3.chipAt(sidePos) shouldBe newChip
                                        side shouldBe LAND
                                    } else {
                                        side shouldBe FALLOUT
                                    }
                            }
                        }
                    }
                    "transition.interimBoard, transition.endBoard" {
                        PrimitiveBoardGenerator().assertAll { initialBoard: PrimitiveBoard ->
                            initialBoard.asPosMap()
                                .filterValues { it?.level == Level(3) }
                                .keys
                                .forAll { startPos: Pos ->
                                    val board = initialBoard.copy()
                                    val transitions = board.incAnimated(startPos)
                                    transitions shouldHaveAtLeastSize 1
                                    transitions.toList().forAll {
                                        it.interimBoard.asPosMap()
                                            .values
                                            .filterNotNull()
                                            .filter { it.level >= Level(4) }
                                            .shouldContainExactly(emptyList())
                                    }
                                    transitions.first().interimBoard.asString()
                                        .shouldBe(
                                            SimpleBoard(initialBoard).apply {
                                                posMap[startPos] = null
                                            }.asString()
                                        )
                                    transitions.last().endBoard.asString() shouldBe board.asString()
                                    board.asPosMap().values
                                        .filterNotNull()
                                        .filter { it.level >= Level(4) }
                                        .shouldContainExactly(emptyList())
                                }
                        }
                    }
                    "example" {
                        val emptyBoard: EmptyBoard = EmptyBoardFactory.square(4).apply {
                            with(EmptyBoardFactory) { symmetricRemove(1, 0) }
                        }
                        fun chip(id: Int, ordinal: Int): Chip =
                            Chip(PlayerId(id), Level(ordinal))
                        val board: Board = SimpleBoard(emptyBoard).with(
                            Pos(0, 0) to chip(2, 3),
                            Pos(0, 1) to chip(1, 3),
                            Pos(0, 2) to chip(1, 3),
                            Pos(0, 3) to chip(1, 1),
                            Pos(1, 1) to chip(1, 3),
                            Pos(1, 2) to chip(0, 2),
                            Pos(2, 1) to chip(3, 2),
                            Pos(2, 2) to chip(3, 3),
                            Pos(3, 0) to chip(3, 3),
                            Pos(3, 1) to chip(3, 2),
                            Pos(3, 2) to chip(3, 3),
                            Pos(3, 3) to null
                        )
                        // x>0 1 2 3 <x
                        // 0|3²    3³|0
                        // 1|3¹3¹2³2³|1
                        // 2|3¹2⁰3³3³|2
                        // 3|1¹    □ |3
                        // x>0 1 2 3 <x
                        val simpleBoard = SimpleBoard(board)
                        val startPos = Pos(0, 0)
                        val primitiveBoard = PrimitiveBoard(board)
                        val transitions = primitiveBoard.incAnimated(startPos)
                        val endBoard1 = simpleBoard.with(
                            Pos(0, 0) to null,
                            Pos(0, 1) to chip(2, 4)
                        )
                        val transition1 = Transition(
                            simpleBoard.with(Pos(0, 0) to null),
                            endBoard1,
                            setOf(
                                Explosion(
                                    PlayerId(2), Pos(0, 0),
                                    FALLOUT, FALLOUT, LAND, FALLOUT
                                )
                            )
                        )
                        val endBoard2 = endBoard1.with(
                            Pos(0, 0) to chip(2, 1),
                            Pos(0, 1) to null,
                            Pos(0, 2) to chip(2, 4),
                            Pos(1, 1) to chip(2, 4)
                        )
                        val transition2 = Transition(
                            endBoard1.with(Pos(0, 1) to null),
                            endBoard2,
                            setOf(
                                Explosion(
                                    PlayerId(2), Pos(0, 1),
                                    LAND, LAND, LAND, FALLOUT
                                )
                            )
                        )
                        val endBoard3 = endBoard2.with(
                            Pos(0, 1) to chip(2, 2),
                            Pos(0, 2) to null,
                            Pos(0, 3) to chip(2, 2),
                            Pos(1, 1) to null,
                            Pos(1, 2) to chip(2, 4),
                            Pos(2, 1) to chip(2, 3)
                        )
                        val transition3 = Transition(
                            endBoard2.with(Pos(0, 2) to null, Pos(1, 1) to null),
                            endBoard3,
                            setOf(
                                Explosion(
                                    PlayerId(2), Pos(0, 2),
                                    LAND, LAND, LAND, FALLOUT
                                ),
                                Explosion(
                                    PlayerId(2), Pos(1, 1),
                                    FALLOUT, LAND, LAND, LAND
                                )
                            )
                        )
                        val endBoard4 = endBoard3.with(
                            Pos(0, 2) to chip(2, 1),
                            Pos(1, 1) to chip(2, 1),
                            Pos(1, 2) to null,
                            Pos(2, 2) to chip(2, 4)
                        )
                        val transition4 = Transition(
                            endBoard3.with(Pos(1, 2) to null),
                            endBoard4,
                            setOf(
                                Explosion(
                                    PlayerId(2), Pos(1, 2),
                                    LAND, LAND, FALLOUT, LAND
                                )
                            )
                        )
                        val endBoard5 = endBoard4.with(
                            Pos(1, 2) to chip(2, 1),
                            Pos(2, 1) to chip(2, 4),
                            Pos(2, 2) to null,
                            Pos(3, 2) to chip(2, 4)
                        )
                        val transition5 = Transition(
                            endBoard4.with(Pos(2, 2) to null),
                            endBoard5,
                            setOf(
                                Explosion(
                                    PlayerId(2), Pos(2, 2),
                                    LAND, LAND, FALLOUT, LAND
                                )
                            )
                        )
                        val endBoard6 = endBoard5.with(
                            Pos(1, 1) to chip(2, 2),
                            Pos(2, 1) to null,
                            Pos(2, 2) to chip(2, 2),
                            Pos(3, 1) to chip(2, 4),
                            Pos(3, 2) to null,
                            Pos(3, 3) to chip(2, 1)
                        )
                        val transition6 = Transition(
                            endBoard5.with(Pos(2, 1) to null, Pos(3, 2) to null),
                            endBoard6,
                            setOf(
                                Explosion(
                                    PlayerId(2), Pos(2, 1),
                                    FALLOUT, LAND, LAND, LAND
                                ),
                                Explosion(
                                    PlayerId(2), Pos(3, 2),
                                    LAND, FALLOUT, LAND, LAND
                                )
                            )
                        )
                        val endBoard7 = endBoard6.with(
                            Pos(2, 1) to chip(2, 1),
                            Pos(3, 0) to chip(2, 4),
                            Pos(3, 1) to null,
                            Pos(3, 2) to chip(2, 1)
                        )
                        val transition7 = Transition(
                            endBoard6.with(Pos(3, 1) to null),
                            endBoard7,
                            setOf(
                                Explosion(
                                    PlayerId(2), Pos(3, 1),
                                    LAND, FALLOUT, LAND, LAND
                                )
                            )
                        )
                        val endBoard8 = endBoard7.with(
                            Pos(3, 0) to null,
                            Pos(3, 1) to chip(2, 1)
                        )
                        val transition8 = Transition(
                            endBoard7.with(Pos(3, 0) to null),
                            endBoard8,
                            setOf(
                                Explosion(
                                    PlayerId(2), Pos(3, 0),
                                    FALLOUT, FALLOUT, LAND, FALLOUT
                                )
                            )
                        )
                        val expectedTransitions = sequenceOf(
                            transition1, transition2, transition3, transition4,
                            transition5, transition6, transition7, transition8
                        )
                        transitions shouldHaveSize 8
                        transitions.zip(expectedTransitions).toList()
                            .forAll { (actualTransition, expectedTransition) ->
                                actualTransition.interimBoard.asString() shouldBe expectedTransition.interimBoard.asString()
                                actualTransition.endBoard.asString() shouldBe expectedTransition.endBoard.asString()
                                actualTransition.explosions shouldContainExactly expectedTransition.explosions
                            }
                        primitiveBoard.asString() shouldBe endBoard8.asString()
                    }
                }
            }
        }
    }
}