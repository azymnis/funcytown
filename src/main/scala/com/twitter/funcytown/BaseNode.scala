package com.twitter.funcytown

import scala.annotation.tailrec
import scala.collection.immutable.LinearSeq

object Allocator extends LowPriorityAllocator {
  // TODO add disk allocators
}

trait Allocator[@specialized(Long) PtrT] {
  val nullPtr : PtrT
  def deref(ptr : PtrT) : AnyRef
  def ptrOf[T](node : Node[T,PtrT]) : PtrT
  def ptrOf[T](sn : SeqNode[T,PtrT]) : PtrT
  def empty[T](height : Short): PtrNode[T,PtrT]
  def nil[T] : SeqNode[T,PtrT]
  def allocSeq[T](t : T, ptr : PtrT) : SeqNode[T,PtrT]
  def allocLeaf[T](height : Short, pos : Long, value : T) : Leaf[T,PtrT]
  def allocPtrNode[T](sz : Long, height : Short, ptrs : Block[PtrT]) : PtrNode[T,PtrT]
}

object SeqNode {
  def apply[T](items : T*)(implicit mem : Allocator[_]) : SeqNode[T,_] = {
    from(items)(mem)
  }

  def from[T](iter : Iterable[T])(implicit mem : Allocator[_]) = {
    concat(iter,mem.nil[T])
  }

  def concat[T, U >: T, PtrT](iter : Iterable[U], seq : SeqNode[T,PtrT]) : SeqNode[U,PtrT] = {
    // This is safe because of the type constraint
    val sequ : SeqNode[U,PtrT] = seq
    // We reverse as we put in:
    iter.foldRight(sequ) { (newv, old) => newv :: old }
  }
}

/** Allocator agnostic immutable linked list
 */
class SeqNode[+T,PtrT](h : T, t : PtrT, val alloc : Allocator[PtrT]) extends LinearSeq[T] {
  // This is the cons operator:
  def debugStr : String = "(" + h + ", " + t + ")"
  def ::[U >: T](x: U) : SeqNode[U,PtrT] = {
    alloc.allocSeq(x, alloc.ptrOf(this))
  }

  def :::[U >: T](iter : Iterable[U]) : SeqNode[U,PtrT] = SeqNode.concat(iter, this)

  def ++[U >: T](iter : Iterable[U]) : SeqNode[U,PtrT] = {
    val seqiter : SeqNode[U,PtrT] = if (iter.isInstanceOf[SeqNode[U,_]] &&
      (iter.asInstanceOf[SeqNode[U,PtrT]].alloc == alloc)) {
      // No need to convert:
      iter.asInstanceOf[SeqNode[U,PtrT]]
    }
    else {
      SeqNode.from(iter)(alloc).asInstanceOf[SeqNode[U,PtrT]]
    }
    SeqNode.concat(this, seqiter)
  }

  override def foldRight[U](init : U)(foldfn : (T,U) => U) : U = {
    reverse.foldLeft(init) { (prev, item) => foldfn(item, prev) }
  }

  override def foldLeft[U](init : U)(foldfn : (U,T) => U) : U = {
    toStream.foldLeft(init)(foldfn)
  }

  def longLength : Long = {
    @tailrec
    def lenacc(acc : Long, list : Seq[_]) : Long = {
      if (list.isEmpty)
        acc
      else
        lenacc(acc + 1L, list.tail)
    }
    lenacc(0L, this)
  }

  override def length : Int = {
    val len = longLength
    if (len <= Int.MaxValue) len.toInt else error("Length: " + len + " can't fit in Int")
  }

  override def apply(idx : Int) : T = get(idx)
  @tailrec
  final def get(idx : Long) : T = {
    if (isEmpty) {
      error("SeqNode is empty, but get(" + idx + ") was called")
    }
    if (idx == 0) {
      h
    }
    else {
      tail.get(idx - 1L)
    }
  }
  override def isEmpty = (t == alloc.nullPtr)
  override def head = h
  override def iterator : Iterator[T] = toStream.iterator
  override lazy val reverse : SeqNode[T,PtrT] = {
    foldLeft(alloc.nil[T]) { (list, item) => item :: list }
  }

  override def tail = alloc.deref(t).asInstanceOf[SeqNode[T,PtrT]]
  override def toStream : Stream[T] = {
    if (isEmpty) {
      Stream.empty
    }
    else {
      Stream.cons(h, tail.toStream)
    }
  }
}

abstract class Node[T,PtrT] {
  def apply(pos : Long) = get(pos).get
  def get(pos : Long) : Option[T] = findLeaf(pos).map { _.value }
  def isEmpty : Boolean = (size == 0)
  def findLeaf(pos : Long) : Option[Leaf[T, PtrT]]
  def put(pos : Long, value : T) : Node[T, PtrT] = {
    // Just replace:
    map(pos) { x => Some(value) }._2
  }
  def take(pos : Long) : (Option[T], Node[T, PtrT]) = {
    // Just erase whatever is there:
    map(pos) { old => None }
  }
  def size : Long
  def map(pos : Long)(fn : Option[T] => Option[T]) : (Option[T], Node[T,PtrT])
  def toStream : Stream[T]
}

class PtrNode[T, PtrT](sz : Long, val height : Short, val ptrs : Block[PtrT],
  mem : Allocator[PtrT])(implicit mf : Manifest[PtrT]) extends Node[T,PtrT] {
  override def findLeaf(pos : Long) : Option[Leaf[T,PtrT]] = {
    val (thisIdx, nextPos) = Block.toBlockIdx(height, pos)
    val nextPtr = ptrs(thisIdx)
    if (mem.nullPtr != nextPtr) {
      val nextNode = mem.deref(nextPtr).asInstanceOf[Node[T,PtrT]]
      nextNode.findLeaf(nextPos)
    }
    else {
      None
    }
  }

  override def map(pos : Long)(fn : Option[T] => Option[T]) : (Option[T], Node[T,PtrT]) = {
    if (pos < maxPos) {
      // This position cannot possibly be in the tree
      val (thisIdx, nextPos) = Block.toBlockIdx(height, pos)
      val nextPtr = ptrs(thisIdx)
      val (oldVal, newPtr, szdelta) = if (mem.nullPtr == nextPtr) {
        val value = fn(None)
        if (value.isDefined) {
          // This is a new value:
          (None, mem.ptrOf(mem.allocLeaf(height, nextPos, value.get)), 1L)
        }
        else {
          // Mapping None => None, weird, but okay.
          (None, mem.nullPtr, 0L)
        }
      }
      else {
        val nextNode = mem.deref(nextPtr).asInstanceOf[Node[T,PtrT]]
        // Replace down the tree:
        val (old, resNode) = nextNode.map(nextPos)(fn)
        (old, mem.ptrOf(resNode), resNode.size - nextNode.size)
      }
      (oldVal, mem.allocPtrNode(sz + szdelta, height, ptrs.updated(thisIdx, newPtr)))
    }
    else {
      val newValue = fn(None)
      if (newValue.isDefined) {
        //We need a level above us:
        val newBlock = Block.alloc[PtrT].updated(0, mem.ptrOf(this))
        mem.allocPtrNode(sz, (height + 1).toShort, newBlock).map(pos)(fn)
      }
      else {
        //None => None
        (None, this)
      }
    }
  }

  private def maxPos : Long = {
    // This is 2^(7*(height + 1))
    1 << (Block.SHIFT * (height + 1))
  }

  override def size = sz

  override def toStream : Stream[T] = {
    // Just get the children streams out in order:
    ptrs.foldLeft(Stream.empty[T]) { (oldStream, newPtr) =>
      if (newPtr != mem.nullPtr) {
        oldStream ++ (mem.deref(newPtr).asInstanceOf[Node[T,PtrT]].toStream)
      }
      else {
        oldStream
      }
    }
  }
}

class Leaf[T,PtrT](val height : Short, val pos : Long, val value : T, mem : Allocator[PtrT])
  (implicit mf : Manifest[PtrT])
  extends Node[T,PtrT] {
  override def isEmpty = false
  override def findLeaf(inpos : Long) = {
    if (pos == inpos) {
      Some(this)
    }
    else {
      None
    }
  }

  override def map(inpos : Long)(fn : Option[T] => Option[T]) : (Option[T], Node[T, PtrT]) = {
    val oldValue = Some(value)
    if (pos == inpos) {
      val newValue = fn(oldValue)
      if (newValue.isDefined) {
        // Replace:
        (oldValue, mem.allocLeaf(height, pos, newValue.get))
      }
      else {
        // This node is now deleted:
        (oldValue, mem.empty(height))
      }
    }
    else {
      //We have to deepen the tree here:
      val newValue = fn(None)
      if (newValue.isDefined) {
        assert(height > 0)
        val resNode = mem.empty(height)
          .put(pos, value)
          .put(inpos, newValue.get)
        (None, resNode)
      }
      else {
        // None => None case, so do nothing:
        (None, this)
      }
    }
  }
  override def size = 1L
  override def toStream = Stream(value)
}
