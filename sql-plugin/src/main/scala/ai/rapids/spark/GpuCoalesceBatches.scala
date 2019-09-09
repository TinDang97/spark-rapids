/*
 * Copyright (c) 2019, NVIDIA CORPORATION.
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

package ai.rapids.spark

import java.util.concurrent.TimeUnit

import scala.collection.mutable.ArrayBuffer

import ai.rapids.cudf.Table

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.{SparkPlan, UnaryExecNode}
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.TaskContext
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}

/**
 * Consumes an Iterator of ColumnarBatches and concatenates them into a single ColumnarBatch.
 * The batches will be closed when this operation is done.
 */
object ConcatAndConsumeAll {
  private def conversionForConcat(cb: ColumnarBatch): Table = try {
    val asStringCat = GpuColumnVector.convertToStringCategoriesIfNeeded(cb)
    try {
      GpuColumnVector.from(asStringCat)
    } finally {
      asStringCat.close()
    }
  } finally {
    cb.close()
  }

  /**
   * Build a single batch from the batches collected so far. If array is empty this will likely
   * blow up.
   * @param arrayOfBatches the batches to concat. This will be consumed and you do not need to
   *                       close any of the batches after this is called.
   * @return a single batch with all of them concated together.
   */
  def buildNonEmptyBatch(arrayOfBatches: Array[ColumnarBatch]): ColumnarBatch = {
    if (arrayOfBatches.length == 1) {
      // Need to convert the strings to string categories to be consistent.
      val table = conversionForConcat(arrayOfBatches(0))
      try {
        GpuColumnVector.from(table)
      } finally {
        table.close()
      }
    } else {
      val tables = arrayOfBatches.map(conversionForConcat)
      try {
        val combined = Table.concatenate(tables: _*)
        try {
          GpuColumnVector.from(combined)
        } finally {
          combined.close()
        }
      } finally {
        tables.foreach(_.close())
      }
    }
  }

  /**
   * Verify that a single batch was returned from the iterator, or if it is empty return an empty
   * batch.
   * @param batches batches to be consumed.
   * @param format the format of the batches in case we need to return an empty batch.  Typically
   *               this is the output of your exec.
   * @return the single batch or an empty batch if needed.  Please be careful that your exec
   *         does not return empty batches as part of an RDD.
   */
  def getSingleBatchWithVerification(batches: Iterator[ColumnarBatch], format: Seq[Attribute]): ColumnarBatch = {
    import collection.JavaConverters._
    if (!batches.hasNext) {
      GpuColumnVector.emptyBatch(format.asJava)
    } else {
      val batch = batches.next()
      if (batches.hasNext) {
        batch.close()
        throw new IllegalStateException("Expected to only receive a single batch")
      }
      batch
    }
  }

  /**
   * Concat batches into a single batch.
   * @param batches iterator of batches to be consumed and concatenated
   * @param format the format of the data in case the iterator is empty and we need to return an
   *               empty batch.  This will typically be output from your executor.
   * @return the resulting batch, even if it is empty.  Please be careful you should not return
   *         empty batches as part of an RDD.  This is here so it is simple to use in other
   *         operations, like join or sort.
   */
  def apply(batches: Iterator[ColumnarBatch], format: Seq[Attribute]): ColumnarBatch = {
    val arrayOfBatches = batches.toArray
    import collection.JavaConverters._
    if (arrayOfBatches.length <= 0) {
      GpuColumnVector.emptyBatch(format.asJava)
    } else {
      buildNonEmptyBatch(arrayOfBatches)
    }
  }
}

object CoalesceGoal {
  def max(a: CoalesceGoal, b: CoalesceGoal): CoalesceGoal = (a, b) match {
    case (RequireSingleBatch, _) => a
    case (_, RequireSingleBatch) => b
    case (PreferSingleBatch, _) => a
    case (_, PreferSingleBatch) => b
    case (TargetSize(aSize), TargetSize(bSize)) if aSize > bSize => a
    case _ => b
  }
}

sealed abstract class CoalesceGoal extends Serializable {
  val targetSize: Long

  def whenTargetExceeded(actualSize: Long): Unit = {}
}

object RequireSingleBatch extends CoalesceGoal {
  override val targetSize: Long = Integer.MAX_VALUE

  override def whenTargetExceeded(actualSize: Long): Unit = {
    throw new IllegalStateException("A single batch is required for this operation." +
      " Please try increasing your partition count.")
  }
}

object PreferSingleBatch extends CoalesceGoal {
  override val targetSize: Long = Integer.MAX_VALUE
}

case class TargetSize(override val targetSize: Long) extends CoalesceGoal {
  assert(targetSize <= Integer.MAX_VALUE)
}

abstract class AbstractGpuCoalesceIterator(iter: Iterator[ColumnarBatch],
    goal: CoalesceGoal,
    numInputRows: SQLMetric,
    numInputBatches: SQLMetric,
    numOutputRows: SQLMetric,
    numOutputBatches: SQLMetric,
    collectTime: SQLMetric,
    concatTime: SQLMetric) extends Iterator[ColumnarBatch] {
  private var onDeck: Option[ColumnarBatch] = None
  private var collectStart: Long = -1

  TaskContext.get().addTaskCompletionListener[Unit](_ => onDeck.foreach(_.close()))

  override def hasNext: Boolean = {
    while (onDeck.isEmpty && iter.hasNext) {
      if (collectStart < 0) {
        collectStart = System.nanoTime()
      }
      val cb = iter.next()
      numInputBatches += 1
      val rows = cb.numRows()
      if (rows > 0) {
        onDeck = Some(cb)
        numInputRows += rows
      } else {
        cb.close()
      }
    }
    onDeck.isDefined
  }

  /**
   * Called first to initialize any state needed for a new batch to be created.
   */
  def initNewBatch(): Unit

  /**
   * Called to add a new batch to the final output batch. The batch passed in will
   * not be closed.  If it needs to be closed it is the responsibility of the child class
   * to do it.
   * @param batch the batch to add in.
   */
  def addBatchToConcat(batch: ColumnarBatch): Unit

  /**
   * Called after all of the batches have been added in.
   * @return the concated batches on the GPU.
   */
  def concatAllAndPutOnGPU(): ColumnarBatch

  /**
   * Called to cleanup any state when a batch is done (even if there was a failure)
   */
  def cleanupConcatIsDone(): Unit

  override def next(): ColumnarBatch = {
    initNewBatch()
    try {
      var numRows: Long = 0 // to avoid overflows
      if (onDeck.isDefined) {
        val cb = onDeck.get
        val rows = cb.numRows()
        if (rows > goal.targetSize) {
          goal.whenTargetExceeded(rows)
        }
        addBatchToConcat(cb)
        onDeck = None
        numRows += rows
      }

      while (numRows < goal.targetSize && onDeck.isEmpty && iter.hasNext) {
        if (collectStart < 0) {
          collectStart = System.nanoTime()
        }
        val cb = iter.next()
        val nextRows = cb.numRows()
        numInputBatches += 1
        if (nextRows > 0) {
          numInputRows += nextRows
          val wouldBeRows = nextRows + numRows
          if (wouldBeRows > goal.targetSize) {
            goal.whenTargetExceeded(wouldBeRows)
            onDeck = Some(cb)
          } else {
            addBatchToConcat(cb)
            numRows = wouldBeRows
          }
        } else {
          // Empty batch just close it now...
          cb.close()
        }
      }
      numOutputRows += numRows
      numOutputBatches += 1
      val collectEnd = System.nanoTime()
      val ret = concatAllAndPutOnGPU()
      val end = System.nanoTime()
      collectTime += TimeUnit.NANOSECONDS.toMillis(collectEnd - collectStart)
      concatTime += TimeUnit.NANOSECONDS.toMillis(end - collectEnd)
      collectStart = -1
      ret
    } finally {
      cleanupConcatIsDone()
    }
  }
}

class GpuCoalesceIterator(iter: Iterator[ColumnarBatch],
    goal: CoalesceGoal,
    numInputRows: SQLMetric,
    numInputBatches: SQLMetric,
    numOutputRows: SQLMetric,
    numOutputBatches: SQLMetric,
    collectTime: SQLMetric,
    concatTime: SQLMetric)
  extends AbstractGpuCoalesceIterator(iter,
    goal,
    numInputRows,
    numInputBatches,
    numOutputRows,
    numOutputBatches,
    collectTime,
    concatTime) {

  private var batches: ArrayBuffer[ColumnarBatch] = ArrayBuffer.empty

  override def initNewBatch(): Unit =
    batches = ArrayBuffer[ColumnarBatch]()

  override def addBatchToConcat(batch: ColumnarBatch): Unit =
    batches += batch

  override def concatAllAndPutOnGPU(): ColumnarBatch = {
    val ret = ConcatAndConsumeAll.buildNonEmptyBatch(batches.toArray)
    // Clear the buffer so we don't close it again (buildNonEmptyBatch closed it for us).
    batches = ArrayBuffer.empty
    ret
  }

  override def cleanupConcatIsDone(): Unit = batches.foreach(_.close())
}

case class GpuCoalesceBatches(child: SparkPlan, goal: CoalesceGoal)
  extends UnaryExecNode with GpuExec {

  override lazy val metrics: Map[String, SQLMetric] = Map(
    "numInputRows" -> SQLMetrics.createMetric(sparkContext, "input rows"),
    "numOutputRows" -> SQLMetrics.createMetric(sparkContext, "output rows"),
    "numInputBatches" -> SQLMetrics.createMetric(sparkContext, "input batches"),
    "numOutputBatches" -> SQLMetrics.createMetric(sparkContext, "output batches"),
    "collectTime" -> SQLMetrics.createTimingMetric(sparkContext, "collect batch time"),
    "concatTime" -> SQLMetrics.createTimingMetric(sparkContext, "concat batch time")
  )

  override protected def doExecute(): RDD[InternalRow] = {
    throw new IllegalStateException("ROW BASED PROCESSING IS NOT SUPPORTED")
  }

  override def output: Seq[Attribute] = child.output

  override def doExecuteColumnar(): RDD[ColumnarBatch] = {
    val numInputRows = longMetric("numInputRows")
    val numInputBatches = longMetric("numInputBatches")
    val numOutputRows = longMetric("numOutputRows")
    val numOutputBatches = longMetric("numOutputBatches")
    val collectTime = longMetric("collectTime")
    val concatTime = longMetric("concatTime")

    val batches = child.executeColumnar()
    batches.mapPartitions { iter =>
      new GpuCoalesceIterator(iter, goal,
        numInputRows, numInputBatches, numOutputRows, numOutputBatches, collectTime, concatTime)
    }
  }
}