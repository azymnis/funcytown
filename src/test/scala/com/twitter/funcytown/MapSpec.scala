package com.twitter.funcytown

import org.specs._
import java.util.Random

class MapSpec extends Specification {
  noDetailedDiffs()
  // Cache the last N objects, so we have to hit disk sometimes
  //implicit val alloc = new CachingDiskAllocator(50)
  implicit val alloc = new GCDiskAllocator(5000)
  //implicit val alloc = new MemoryAllocator
  val ITERS = 100000 // Should be much larger than 100

  "A funcytown.HashMap" should {
    "look like immutable.HashMap" in {
      try {
      // Be sure to use the right object:
      val r = new Random
      var hashmap = com.twitter.funcytown.HashMap(0 -> 0)
      var smap = scala.collection.immutable.HashMap(0 -> 0)
      hashmap.size must be_==(smap.size)
      (1 to ITERS).foreach { iter =>
        val key = r.nextInt % (ITERS/100) // Repeat some keys
        val value = r.nextInt
        val exists = hashmap.contains(key)
        exists must be_==(smap.contains(key))
        if (exists && r.nextBoolean) {
          hashmap = hashmap - key
          smap = smap - key
          hashmap.contains(key) must be_==(false)
        }
        else {
          hashmap = hashmap + (key -> value)
          smap = smap + (key -> value)
          hashmap.contains(key) must be_==(true)
        }
        (hashmap.size must be_==(smap.size))
      }
      }
      catch {
        case t : Throwable => { t.printStackTrace; throw t }
      }
    }
  }
}
