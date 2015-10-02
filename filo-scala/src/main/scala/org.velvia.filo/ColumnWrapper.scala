package org.velvia.filo

import com.google.flatbuffers.Table
import java.nio.ByteBuffer
import org.velvia.filo.column._
import scala.collection.Traversable
import scala.language.postfixOps
import scalaxy.loops._

/**
 * A ColumnWrapper gives collection API semantics around the binary Filo format vector.
 */
trait ColumnWrapper[@specialized(Int, Double, Long, Short) A] extends Traversable[A] {
  // Returns true if the element at position index is available, false if NA
  def isAvailable(index: Int): Boolean

  // Calls fn for each available element in the column.  Will call 0 times if column is empty.
  def foreach[B](fn: A => B): Unit

  /**
   * Returns the element at a given index.  If the element is not available, the value returned
   * is undefined.  This is a very low level function intended for speed, not safety.
   * @param index the index in the column to pull from.  No bounds checking is done.
   */
  def apply(index: Int): A

  /**
   * Same as apply(), but returns Any, forcing to be an object.
   */
  def boxed(index: Int): Any = apply(index).asInstanceOf[Any]

  /**
   * Returns the number of elements in the column.
   */
  def length: Int

  /**
   * A "safe" but slower get-element-at-position method.
   * @param index the index in the column to get
   * @return Some(a) if index is within bounds and element is not missing
   */
  def get(index: Int): Option[A] =
    if (index >= 0 && index < length && isAvailable(index)) { Some(apply(index)) }
    else                                                    { None }

  /**
   * Returns an Iterator[Option[A]] over the Filo bytebuffer.  This basically calls
   * get() at each index, so it returns Some(A) when the value is defined and None
   * if it is NA.
   */
  def optionIterator(): Iterator[Option[A]] =
    for { index <- (0 until length).toIterator } yield { get(index) }
}

/**
 * Represents either an empty column (length 0) or a column where none of the
 * values are available (null).
 */
class EmptyColumnWrapper[A](len: Int) extends ColumnWrapper[A] {
  final def isAvailable(index: Int): Boolean = false
  final def foreach[B](fn: A => B): Unit = {}
  final def apply(index: Int): A =
    if (index < len) { null.asInstanceOf[A] }
    else { throw new ArrayIndexOutOfBoundsException }
  final def length: Int = len
}

// TODO: separate this out into traits for AllZeroes vs mixed ones for speed, no need to do
// if sc.naMask.maskType lookup every time
// TODO: wrap bitMask with FastBufferReader for speed
trait NaMaskAvailable {
  val naMask: NaMask
  lazy val maskLen = naMask.bitMaskLength()
  lazy val maskReader = FastBufferReader(naMask.bitMaskAsByteBuffer)
  lazy val maskType = naMask.maskType

  final def isAvailable(index: Int): Boolean = {
    if (maskType == MaskType.AllZeroes) {
      true
    } else {
      // NOTE: length of bitMask may be less than (length / 64) longwords.
      val maskIndex = index >> 5
      val maskVal = if (maskIndex < maskLen) maskReader.readLong(maskIndex) else 0L
      (maskVal & (1 << (index & 63))) == 0
    }
  }
}

abstract class SimpleColumnWrapper[A](sc: SimpleColumn, vector: Table)
    extends ColumnWrapper[A] with NaMaskAvailable {
  val naMask = sc.naMask

  final def length: Int = VectorUtils.getLength(vector)

  final def foreach[B](fn: A => B): Unit = {
    for { i <- 0 until length optimized } { if (isAvailable(i)) fn(apply(i)) }
  }
}

object DictStringColumnWrapper {
  // Used to represent no string value or NA.  Better than using null.
  val NoString = ""
}

abstract class DictStringColumnWrapper(val dsc: DictStringColumn, vector: Table)
    extends ColumnWrapper[String] {
  import DictStringColumnWrapper._

  // To be mixed in depending on type of code vector
  def getCode(index: Int): Int

  // Cache the Strings so we only pay cost of deserializing each unique string once
  val strCache = Array.fill(dsc.dictionaryLength())(NoString)

  final private def dictString(code: Int): String = {
    val cacheValue = strCache(code)
    if (cacheValue == NoString) {
      val strFromDict = dsc.dictionary(code)
      strCache(code) = strFromDict
      strFromDict
    } else {
      cacheValue
    }
  }

  final def isAvailable(index: Int): Boolean = getCode(index) != 0

  final def apply(index: Int): String = dictString(getCode(index))

  final def length: Int = VectorUtils.getLength(vector)

  final def foreach[B](fn: String => B): Unit = {
    for { i <- 0 until length optimized } {
      val code = getCode(i)
      if (code != 0) fn(dictString(code))
    }
  }
}