/*
 * Twitter Korean Text - Scala library to process Korean text
 *
 * Copyright 2014 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.penguin.korean.tokenizer

import com.twitter.penguin.korean.tokenizer.KoreanChunker._
import com.twitter.penguin.korean.util.KoreanDictionaryProvider._
import com.twitter.penguin.korean.util.KoreanPos
import com.twitter.penguin.korean.util.KoreanPos._
import com.twitter.penguin.korean.util.KoreanSubstantive._
import com.twitter.penguin.korean.tokenizer.KoreanTokenizer._

import scala.collection.JavaConversions._
import scala.collection.mutable

/**
 * Provides Korean tokenization.
 *
 * Chunk: 어절 - 공백으로 구분되어 있는 단위 (사랑하는사람을)
 * Word: 단어 - 하나의 문장 구성 요소 (사랑하는, 사람을)
 * Token: 토큰 - 형태소와 비슷한 단위이지만 문법적으로 정확하지는 않음 (사랑, 하는, 사람, 을)
 *
 * Whenever there is an updates in the behavior of KoreanParser,
 * the initial cache has to be updated by running tools.CreateInitialCache.
 */
object KoreanScorer {
  private val TOP_N_PER_STATE = 5
  private val MAX_TRACE_BACK = 8

  private val WEIGHT_TOKENS = 0.18f
  private val WEIGHT_UNKNOWNS = 0.3f
  private val WEIGHT_WORDS = 0.3f
  private val WEIGHT_UNKNOWN_COVERAGE = 0.5f
  private val WEIGHT_FREQ = 0.2f
  private val WEIGHT_POS_UNKNOWNS = 10.0f
  private val WEIGHT_EXACT_MATCH = 0.5f
  private val WEIGHT_ALL_NOUN = 0.1f
  private val WEIGHT_PREFFERED_PATTERN = 0.6f
  private val WEIGHT_DETERMINER = -0.01f

  private val PREFERRED_PATTERN = Seq(Noun, Josa)

  /**
   * A candidate parse for a chunk.
   *
   * @param posNodes Sequence of KoreanTokens.
   * @param words Number of words in this candidate parse.
   */
  case class ParsedChunk(posNodes: Seq[KoreanToken], words: Int) {
    def ++(that: ParsedChunk) = {
      ParsedChunk(this.posNodes ++ that.posNodes, this.words + that.words)
    }

    lazy val score = countTokens * WEIGHT_TOKENS +
        countUnknowns * WEIGHT_UNKNOWNS +
        words * WEIGHT_WORDS +
        getUnknownCoverage * WEIGHT_UNKNOWN_COVERAGE +
        getFreqScore * WEIGHT_FREQ +
        countPos(Unknown) * WEIGHT_POS_UNKNOWNS +
        isExactMatch * WEIGHT_EXACT_MATCH +
        isAllNouns * WEIGHT_ALL_NOUN +
        isPreferredPattern * WEIGHT_PREFFERED_PATTERN +
        countPos(Determiner) * WEIGHT_DETERMINER

    lazy val countUnknowns = this.posNodes.count { p: KoreanToken => p.unknown}
    lazy val countTokens = this.posNodes.size

    lazy val isExactMatch = if (this.posNodes.size == 1) 0 else 1
    lazy val isAllNouns = if (this.posNodes.exists(_.pos != Noun)) 1 else 0
    lazy val isPreferredPattern = if (posNodes.size == 2 && posNodes.map(_.pos) == PREFERRED_PATTERN) 0 else 1

    lazy val posTieBreaker = this.posNodes.map(_.pos.id).sum

    lazy val getUnknownCoverage = this.posNodes.foldLeft(0) {
      case (sum, p: KoreanToken) => if (p.unknown) sum + p.text.length else sum
    }

    lazy val getFreqScore = this.posNodes.foldLeft(0f) {
      case (output: Float, p: KoreanToken) if p.pos == Noun => output + (1f - koreanEntityFreq.getOrElse(p.text, 0f))
      case (output: Float, p: KoreanToken) => output + 1.0f
    } / this.posNodes.size

    def countPos(pos: KoreanPos) = this.posNodes.count { p: KoreanToken => p.pos == pos}
    def getScore() = this.score
  }

  /**
   * 0 for optional, 1 for required
   * * for optional repeatable, + for required repeatable
   *
   * Substantive: 체언 (초거대기업의)
   * Predicate: 용언 (하였었습니다, 개예뻤었다)
   * Modifier: 수식언 (모르는 할수도있는 보이기도하는 예뻐 예쁜 완전 레알 초인간적인 잘 잘한)
   * Standalone: 독립언
   * Functional: 관계언 (조사)
   *
   * N Noun: 명사 (Nouns, Pronouns, Company Names, Proper Noun, Person Names, Numerals, Standalone, Dependent)
   * V Verb: 동사 (하, 먹, 자, 차)
   * J Adjective: 형용사 (예쁘다, 크다, 작다)
   * A Adverb: 부사 (잘, 매우, 빨리, 반드시, 과연)
   * D Determiner: 관형사 (새, 헌, 참, 첫, 이, 그, 저)
   * E Exclamation: 감탄사 (헐, ㅋㅋㅋ, 어머나, 얼씨구)
   *
   * C Conjunction: 접속사
   *
   * j SubstantiveJosa: 조사 (의, 에, 에서)
   * l AdverbialJosa: 부사격 조사 (~인, ~의, ~일)
   * e Eomi: 어말어미 (다, 요, 여, 하댘ㅋㅋ)
   * r PreEomi: 선어말어미 (었)
   *
   * p NounPrefix: 접두사 ('초'대박)
   * v VerbPrefix: 동사 접두어 ('쳐'먹어)
   * s Suffix: 접미사 (~적)
   */
  private val SequenceDefinition = Map(
    // Substantive
    "D0p*N1s0j0" -> Noun,
    // Predicate 초기뻐하다, 와주세요, 초기뻤었고, 추첨하다, 구경하기힘들다, 기뻐하는, 기쁜, 추첨해서, 좋아하다, 걸려있을
    "v*V1r*e0" -> Verb,
    "v*J1r*e0" -> Adjective,
    // Modifier 부사
    "A1" -> Adverb,
    // Standalone
    "C1" -> Conjunction,
    "E+" -> Exclamation,
    "j1" -> Josa
  )

  private val koreanPosTrie = KoreanPos.getTrie(SequenceDefinition)

  case class ParsedChunkWithMinScore(parsedChunk: Option[ParsedChunk], score: Float)

  case class CandidateParse(parse: ParsedChunk, curTrie: List[KoreanPosTrie], ending: Option[KoreanPos])

  case class PossibleTrie(curTrie: KoreanPosTrie, words: Int)

  /**
   * Find the best parse using dynamic programming.
   *
   * @param chunk Input chunk. The input has to be entirely. Check for input validity is skipped
   *              for performance optimization. This method is private and is called only by tokenize.
   * @return The best possible parse.
   */
  private[this] def parseKoreanChunkAndGetScore(chunk: String): Seq[Float] = {

    // Direct match
    koreanDictionary.foreach {
      case (pos, dict) =>
        if (dict.contains(chunk)) {
          return Seq(1f)
        }
    }

    // Buffer for solutions
    val solutions: mutable.Map[Int, List[CandidateParse]] = new java.util.HashMap[Int, List[CandidateParse]]

    // Initial state
    solutions += 0 -> List(
      CandidateParse(
        ParsedChunk(Seq[KoreanToken](), 1),
        koreanPosTrie, ending = None
      )
    )

    // Find N best parses per state
    for (
      end <- 1 to chunk.length;
      start <- end - 1 to(Seq(end - MAX_TRACE_BACK, 0).max, -1)
    ) {
      val word = chunk.slice(start, end)

      val curSolutions = solutions(start)

      val candidates = curSolutions.flatMap {
        solution =>
          val possiblePoses: Seq[PossibleTrie] = if (solution.ending.isDefined) {
            solution.curTrie.map(t => PossibleTrie(t, 0)) ++ koreanPosTrie.map(t => PossibleTrie(t, 1))
          } else {
            solution.curTrie.map(t => PossibleTrie(t, 0))
          }

          possiblePoses.view.filter { t =>
            t.curTrie.curPos == Noun || koreanDictionary(t.curTrie.curPos).contains(word.toCharArray)
          }.map { case t: PossibleTrie =>
            val candidateToAdd =
              if (t.curTrie.curPos == Noun && !koreanDictionary(Noun).contains(word.toCharArray)) {
                val unknown = !isName(word) && !isKoreanNumber(word) && !isKoreanNameVariation(word)
                ParsedChunk(Seq(KoreanToken(word, Noun, unknown)), t.words)
              } else {
                ParsedChunk(Seq(KoreanToken(word, t.curTrie.curPos)), t.words)
              }

            val nextTrie = t.curTrie.nextTrie.map {
              case nt: KoreanPosTrie if nt == selfNode => t.curTrie
              case nt: KoreanPosTrie => nt
            }

            CandidateParse(solution.parse ++ candidateToAdd, nextTrie, t.curTrie.ending)
          }
      }

      val currentSolutions = if (solutions.contains(end)) solutions(end) else List()

      solutions += end -> (currentSolutions ++ candidates).sortBy {
        c => (c.parse.score, c.parse.posTieBreaker)
      }.take(TOP_N_PER_STATE)
    }

    // Return the best parse of the final state
    Seq(solutions(chunk.length).minBy(c => c.parse.score).parse.score)
  }

  /**
   * Parse Korean text into a sequence of KoreanTokens
   *
   * @param text Input Korean chunk
   * @return sequence of KoreanTokens
   */
  def score(text: CharSequence, keepSpace: Boolean = false): Seq[Float] = {
    chunk(text, keepSpace).flatMap {
      case token: KoreanToken if token.pos == Korean =>
        parseKoreanChunkAndGetScore(token.text)

      case token: KoreanToken => Seq(0f)
    }
  }
}
