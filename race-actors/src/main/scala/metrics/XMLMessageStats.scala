/*
 * Copyright (c) 2016, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gov.nasa.race.actors.metrics

import java.io.{FileOutputStream, BufferedOutputStream, File, OutputStream}

import akka.actor.{ActorRef, Cancellable, ActorSystem}
import gov.nasa.race.common._

import scala.xml.{XML}

import com.typesafe.config.Config

import gov.nasa.race.core.{BusEvent, SubscribingRaceActor, PublishingRaceActor}
import gov.nasa.race.data.MessageStats

import org.joda.time.{DateTime,Period}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import java.io._


/**
  * A generic actor that parses xml messages, retreives (sub)messages at the given
  * level, and produces statistics for each tag defining the message.
  *
  * <2do> pcm - this should probably be based on simTime so that it can also be used in simulations
  *
  * __[R-100.7.1]__  A generic actor that parses xml messages, retreives (sub)messages at the given
  * level, and produces statistics for each tag defining the message.
  */

class XMLMessageStats (val config: Config) extends SubscribingRaceActor with PublishingRaceActor {

  //level at which tags are collected
  val tagLevel = config.getString("tag-level").toInt

  // setting the channel
  val readFromChannel = config.getString("read-from")

  // if reportInterval or reportFrequency are not set, only one final report is printed.
  val reportInterval = config.getString("report-interval").toInt
  val reportFrequency = config.getString("report-frequency").toInt

  // directory at which the results are saved
  val pathName = config.getString("pathname")

  val facility = config.getString("facility")

  val totalTag = s"* total $readFromChannel msgs *"

  // container for total messages since the actor started
  val totalMs = new MessageStats(totalTag,1)

  val numMaps = if(reportInterval>0 && reportFrequency>0) Math.ceil(reportInterval/reportFrequency).toInt else 1

  val intervalTag = s"* interval *"

  var intervalStart = Array.fill[DateTime](numMaps)(DateTime.now)

  var maps = Array.fill[Map[String, MessageStats]](numMaps)(getNewMap())

  var schedule: Option[Cancellable] = None

  // index for the next map to be printed
  var idx = 0

  override def handleMessage = {
      case BusEvent(_,xml:String,_) if !xml.isEmpty =>
        log.info(s"collecting stats ${xml.substring(0,20)}..")
        stats(xml)
  }

  /**
   * it creates a map and adds one MessageStats to it which includes stats for total
   * messages observed in the interval
   */
  def getNewMap() : Map[String,MessageStats] = {
    // create an element for total
    val ms = new MessageStats(intervalTag,1)

    val map : Map[String, MessageStats] = Map(intervalTag -> ms)

    map
  }

  def stats(s: String) = {
    for(map <- maps){
      // increase global counters
      val ms = map.get(intervalTag).get
      ms.addSize(s.getBytes.length)
      ms.addNum(1)
    }

    // recursively collect information from the message
    getMsgStats(s.substring(s.indexOf(">")+1,s.length).replaceAll("^\\s+", ""), 1);

    //increase global counters
    totalMs.addSize(s.getBytes.length)
    totalMs.addNum(1)
  }

  def getMsgStats(s: String, level: Int) : Unit = {
    val doc = XML.loadString(s)
    val tag = s"<${doc.label}>"
    log.info(s"Found tag: $tag")

    for(i <- 0 until maps.size) {

      //create MessageStats data
      var ms: MessageStats = null
      if (maps(i).contains(tag)) {
        //update the value
        ms = maps(i).get(tag).get
      } else {
        // add the value
        ms = new MessageStats(tag, level)
      }

      ms.addSize(s.length())
      ms.addNum(1)
      maps(i) += (tag -> ms)
    }

    if(level<tagLevel) {
      for(e <- doc \ "_") {
        log.info(s"Found sub-msg: $e")
        //collect stats on each sub-msg
        getMsgStats(e.toString,level+1);
      }
    }
  }

  //--- overridden system hooks

  var start : DateTime = null

  var writeReport = false

  val fullpath = pathName + facility + "/"

  override def onStartRaceActor(originator: ActorRef) = {
    super.onStartRaceActor(originator)

    new File(fullpath).mkdirs()
    log.info(s"created $fullpath")

    if(reportFrequency>0 && reportInterval>0) {

      schedule = Some(context.system.scheduler.schedule(
        reportFrequency minutes, // initial delay
        reportFrequency minutes, // frequency
        new Runnable {
          override def run = {
            val time = DateTime.now

            if(writeReport) {
              writeResult(result(maps(idx),intervalStart(idx)))
            } else {
              val period = new Period(start,DateTime.now).toStandardMinutes.getMinutes
              if(period > reportInterval-reportFrequency) {
                writeResult(result(maps(idx),intervalStart(idx)))
                writeReport = true
              }
            }

            setNextMap(time)
          }
        })) // function to be repeated
    }

    log.info(s"${name} simulation started: ${DateTime.now}")

    start = DateTime.now

  }

  def setNextMap(time: DateTime) : Unit = {
    intervalStart(idx) = time
    maps(idx) = getNewMap

    idx = if(idx==maps.size-1) 0 else idx+1
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    super.onTerminateRaceActor(originator)

    log.info(s"${name} terminating")

    val report: String = result(maps(idx), intervalStart(idx))
    log.info(s"Final results: \n\n$report")

    println(s"Final results: \n\n$report")

    ifSome(schedule){ _.cancel }
  }

  // pretty-print

  def writeResult(result: String): Unit = {
    val path = fullpath + DateTime.now.toLocalTime + ".txt"
    val file =  new File(path)
    val output = new BufferedOutputStream( new FileOutputStream(file, false))
    output.write(result.getBytes);
    output.close()
  }

  var end : DateTime = null

  def result (map: Map[String,MessageStats], startTime: DateTime): String = {
    end = DateTime.now

    val intervalPeriod = new Period(startTime,end)
    val totalPeriod = new Period(start,end)

    var s = s"channel: $readFromChannel        facility: $facility\n\n" +
      s"total period: ${start.toLocalDateTime} - ${end.toLocalDateTime}\n" +
      s"total duration: ${totalPeriod}\n\n" +
      s"interval period: ${startTime.toLocalDateTime} - ${end.toLocalDateTime}\n" +
      s"interval duration: ${intervalPeriod}\n\n" +
      s"${MessageStats.header}\n"

    for ((k,v) <- map) {
      v.computeRates(intervalPeriod.toStandardSeconds.getSeconds)
      s += v.formatted
    }

    totalMs.computeRates(totalPeriod.toStandardSeconds.getSeconds)

    s += s"${MessageStats.solidLine}\n${totalMs.formatted}${MessageStats.solidLine}"

    s
  }
}
