/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect
package internals

import java.util.concurrent.atomic.AtomicReference
import cats.effect.concurrent.MVar
import cats.effect.internals.Callback.rightUnit
import scala.annotation.tailrec

/**
 * [[MVar]] implementation for [[Concurrent]] data types.
 */
private[effect] final class MVarConcurrent[F[_], A] private (
  initial: MVarConcurrent.State[A])(implicit F: Concurrent[F])
  extends MVar[F, A] {

  import MVarConcurrent._

  /** Shared mutable state. */
  private[this] val stateRef = new AtomicReference[State[A]](initial)

  def put(a: A): F[Unit] =
    F.cancelable(unsafePut(a))

  def take: F[A] =
    F.cancelable(unsafeTake)

  def read: F[A] =
    F.cancelable(unsafeRead)

  @tailrec private def unsafePut(a: A)(onPut: Listener[Unit]): IO[Unit] = {
    stateRef.get match {
      case current @ WaitForTake(value, listeners) =>
        val id = new Id
        val newMap = listeners.updated(id, (a, onPut))
        val update = WaitForTake(value, newMap)

        if (stateRef.compareAndSet(current, update)) {
          IO(unsafeCancelPut(id))
        } else {
          unsafePut(a)(onPut) // retry
        }

      case current @ WaitForPut(reads, takes) =>
        var first: Listener[A] = null
        val update: State[A] =
          if (takes.isEmpty) State(a) else {
            val (x, rest) = takes.dequeue
            first = x
            if (rest.isEmpty) State.empty[A]
            else WaitForPut(LinkedMap.empty, rest)
          }

        if (stateRef.compareAndSet(current, update)) {
          val value = Right(a)
          // Satisfies all current `read` requests found
          streamAll(value, reads)
          // Satisfies the first `take` request found
          if (first ne null) first(value)
          // Signals completion of `put`
          onPut(rightUnit)
          IO.unit
        } else {
          unsafePut(a)(onPut) // retry
        }
    }
  }

  // Impure function meant to cancel the put request
  @tailrec private def unsafeCancelPut(id: Id): Unit =
    stateRef.get() match {
      case current @ WaitForTake(_, listeners) =>
        val update = current.copy(listeners = listeners - id)
        if (!stateRef.compareAndSet(current, update)) {
          unsafeCancelPut(id) // retry
        }
      case _ =>
        ()
    }

  @tailrec
  private def unsafeTake(onTake: Listener[A]): IO[Unit] = {
    stateRef.get match {
      case current @ WaitForTake(value, queue) =>
        if (queue.isEmpty) {
          if (stateRef.compareAndSet(current, State.empty)) {
            onTake(Right(value))
            IO.unit
          } else {
            unsafeTake(onTake) // retry
          }
        } else {
          val ((ax, notify), xs) = queue.dequeue
          if (stateRef.compareAndSet(current, WaitForTake(ax, xs))) {
            onTake(Right(value))
            notify(rightUnit)
            IO.unit
          } else {
            unsafeTake(onTake) // retry
          }
        }

      case current @ WaitForPut(reads, takes) =>
        val id = new Id
        val newQueue = takes.updated(id, onTake)
        if (stateRef.compareAndSet(current, WaitForPut(reads, newQueue)))
          IO(unsafeCancelTake(id))
        else {
          unsafeTake(onTake) // retry
        }
    }
  }

  @tailrec private def unsafeCancelTake(id: Id): Unit =
    stateRef.get() match {
      case current @ WaitForPut(reads, takes) =>
        val newMap = takes - id
        val update: State[A] = WaitForPut(reads, newMap)
        if (!stateRef.compareAndSet(current, update)) {
          unsafeCancelTake(id)
        }
      case _ =>
    }


  @tailrec
  private def unsafeRead(onRead: Listener[A]): IO[Unit] = {
    val current: State[A] = stateRef.get
    current match {
      case WaitForTake(value, _) =>
        // A value is available, so complete `read` immediately without
        // changing the sate
        onRead(Right(value))
        IO.unit

      case WaitForPut(reads, takes) =>
        // No value available, enqueue the callback
        val id = new Id
        val newQueue = reads.updated(id, onRead)
        if (stateRef.compareAndSet(current, WaitForPut(newQueue, takes)))
          IO(unsafeCancelRead(id))
        else {
          unsafeRead(onRead) // retry
        }
    }
  }

  private def unsafeCancelRead(id: Id): Unit =
    stateRef.get() match {
      case current @ WaitForPut(reads, takes) =>
        val newMap = reads - id
        val update: State[A] = WaitForPut(newMap, takes)
        if (!stateRef.compareAndSet(current, update)) {
          unsafeCancelRead(id)
        }
      case _ => ()
    }

  // For streaming a value to a whole `reads` collection
  private def streamAll(value: Either[Nothing, A], listeners: LinkedMap[Id, Listener[A]]): Unit = {
    val cursor = listeners.values.iterator
    while (cursor.hasNext)
      cursor.next().apply(value)
  }
}

private[effect] object MVarConcurrent {
  /** Builds an [[MVarConcurrent]] instance with an `initial` value. */
  def apply[F[_], A](initial: A)(implicit F: Concurrent[F]): MVar[F, A] =
    new MVarConcurrent[F, A](State(initial))

  /** Returns an empty [[MVarConcurrent]] instance. */
  def empty[F[_], A](implicit F: Concurrent[F]): MVar[F, A] =
    new MVarConcurrent[F, A](State.empty)

  /**
   * Internal API — Matches the callback type in `cats.effect.Async`,
   * but we don't care about the error.
   */
  private type Listener[-A] = Either[Nothing, A] => Unit

  /** Used with [[LinkedMap]] to identify callbacks that need to be cancelled. */
  private final class Id extends Serializable

  /** ADT modelling the internal state of `MVar`. */
  private sealed trait State[A]

  /** Private [[State]] builders.*/
  private object State {
    private[this] val ref = WaitForPut[Any](LinkedMap.empty, LinkedMap.empty)
    def apply[A](a: A): State[A] = WaitForTake(a, LinkedMap.empty)
    /** `Empty` state, reusing the same instance. */
    def empty[A]: State[A] = ref.asInstanceOf[State[A]]
  }

  /**
   * `MVarConcurrent` state signaling it has `take` callbacks
   * registered and we are waiting for one or multiple
   * `put` operations.
   */
  private final case class WaitForPut[A](
    reads: LinkedMap[Id, Listener[A]], takes: LinkedMap[Id, Listener[A]])
    extends State[A]

  /**
   * `MVarConcurrent` state signaling it has one or more values enqueued,
   * to be signaled on the next `take`.
   *
   * @param value is the first value to signal
   * @param listeners are the rest of the `put` requests, along with the
   *        callbacks that need to be called whenever the corresponding
   *        value is first in line (i.e. when the corresponding `put`
   *        is unblocked from the user's point of view)
   */
  private final case class WaitForTake[A](
    value: A, listeners: LinkedMap[Id, (A, Listener[Unit])])
    extends State[A]
}

