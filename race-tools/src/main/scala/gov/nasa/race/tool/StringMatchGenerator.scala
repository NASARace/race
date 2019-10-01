/*
 * Copyright (c) 2019, United States Government, as represented by the
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
package gov.nasa.race.tool

import gov.nasa.race._
import gov.nasa.race.util.StringUtils._
import scala.annotation.tailrec

class PrefixTree {

  class Node(var value: String, // this does not include the prefix (combined parent values)
                  var isWord: Boolean,
                  var parent: Option[Node],
                  var firstChild: Option[Node] = None,
                  var nextSibling: Option[Node] = None) {

    def isFirstSibling: Boolean = {
      parent match {
        case Some(node) => node.firstChild.get eq this
        case None => this eq root.get
      }
    }

    def hasNextSibling: Boolean = nextSibling.isDefined

    protected def lastSibling: Option[Node] = {
      if (nextSibling.isDefined) {
        var n = nextSibling.get
        while (n.nextSibling.isDefined) n = n.nextSibling.get
        Some(n)
      } else None
    }

    def level: Int = {
      var level = 0
      var n = this
      while (n.parent.isDefined){
        n = n.parent.get
        level += 1
      }
      level
    }

    def isRoot: Boolean = this eq root.get

    def fullValue: String = {
      @tailrec def accValues(node: Node, acc: String): String = {
        if (node.parent.isDefined){
          accValues(node.parent.get, node.value + acc)
        } else {
          node.value + acc
        }
      }

      accValues(this,"")
    }

    def combinedCharPrefixLength: Int = {
      @tailrec def computePrefixLength (n: Node, l: Int): Int = {
        if (n.parent.isDefined) {
          val pn = parent.get
          computePrefixLength(pn, n.value.length + l)
        } else {
          n.value.length + l
        }
      }

      if (parent.isDefined) computePrefixLength(parent.get, 0) else 0
    }

    def combinedBytePrefixLength: Int = {
      @tailrec def computePrefixLength (n: Node, l: Int): Int = {
        if (n.parent.isDefined) {
          val pn = parent.get
          computePrefixLength(pn, n.value.getBytes.length + l)
        } else {
          n.value.getBytes.length + l
        }
      }

      if (parent.isDefined) computePrefixLength(parent.get, 0) else 0
    }

    def dumpNode (level: Int): Unit = {
      for (i <- 0 until level) print(' ')
      print(value)
      if (isWord) print(s" ('$fullValue')")
      println
      for (n <- firstChild) {
        n.dumpNode(level + value.length)
      }
      for (n <- nextSibling) {
        n.dumpNode(level)
      }
    }

    def add (s: String): Unit = {
      val pl = prefixLength(value, s)
      val sl = s.length

      if (pl == 0) {         // no common prefix - add as new word sibling to current node (append to preserve lookup order)
        nextSibling match {
          case Some(node) => node.add(s)
          case None => nextSibling = Some(new Node(s,true,parent))
        }

      } else if (pl < sl) {  // node value is a proper prefix of 's' - add as new child to current node (word or prefix)
        if (pl < value.length) {     // current node is a partial prefix of 's' - split
          val ss1 = value.substring(pl)
          val ss2 = s.substring(pl)

          val n2 = new Node(ss2,true,Some(this)) // no children, no sibling
          val n1 = new Node(ss1,true,Some(this),firstChild,Some(n2))

          // parent does not change
          isWord = false
          value = value.substring(0,pl)
          firstChild = Some(n1)
          // nextSibling does no change

        } else {                    // current node is a full prefix of 's', add as child
          val ss = s.substring(pl)
          firstChild match {
            case Some(node) => node.add(ss)
            case None => firstChild = Some(new Node(ss, true, Some(this)))
          }
        }

      } else {                     // pl == sl (can't be > since we only compare up min lengths)
        if (value.length == pl) {  // s is identical to node value - set 'isWord' of current and we are done
          isWord = true

        } else {                   // s is prefix of current value - shorten value and add new child
          val ss = value.substring(sl)
          value = s
          isWord = true
          // parent does not change
          firstChild = Some(new Node(ss,isWord,Some(this),firstChild,None))
          // nextSibling does not change
        }
      }
    }

    @tailrec final def replaceSibling(oldNode: Node, newNode: Node): Unit = {
      if (nextSibling.isDefined){
        val n = nextSibling.get
        if (n eq oldNode) nextSibling = Some(newNode) else n.replaceSibling(oldNode,newNode)
      }
    }

    def replaceChild (oldNode: Node, newNode: Node): Unit = {
      if (firstChild.isDefined) {
        val n = firstChild.get
        if (n eq oldNode) {
          firstChild = Some(newNode)
        } else {
          n.replaceSibling(oldNode, newNode)
        }
      }
    }

    def walkDF (f: Node=>Unit): Unit = {
      f(this)
      ifSome(firstChild) { _.walkDF(f) }
      ifSome(nextSibling) { _.walkDF(f) }
    }

    def walkDFWrapped (fDown: Node=>Unit, fUp: Node=>Unit): Unit = {
      fDown(this)
      ifSome(firstChild) { _.walkDFWrapped(fDown,fUp) }
      fUp(this)
      ifSome(nextSibling) { _.walkDFWrapped(fDown,fUp) }
    }
  }

  var root: Option[Node] = None

  def isEmpty: Boolean = !root.isDefined

  def dump: Unit = {
    for (n <- root) n.dumpNode(0)
  }

  def += (s: String): Unit = {
    if (root.isDefined) {
      root.get.add(s)
    } else { // first entry
      root = Some(new Node(s,true,None))
    }
  }

  def walkDF (f: Node=>Unit): Unit = {
    ifSome(root) { _.walkDF(f) }
  }

  def walkDFWrapped (fDown: Node=>Unit, fUp: Node=>Unit): Unit = {
    ifSome(root) { _.walkDFWrapped(fDown,fUp) }
  }
}

/**
  * a tool that generates generic function templates for string set recognizers
  */
object StringMatchGenerator {

  def main (args: Array[String]): Unit = {
    if (args.length < 2) {
      println("usage: smg <cmd> string, ...")
      println("supported commands:")
      println("  show   print prefix tree")
      println("  gen    generate Array[Byte] based recognizer")
      println("  genc   generate string/char based recognizer")
    }

    val cmd = args(0)
    val strings = args.tail

    cmd match {
      case "show" => printTree(strings)
      case "gen" => generateByteRecognizer(strings)
      case "genc" => generateCharRecognizer(strings)
      case s => println(s"unknown command $s")
    }
  }

  def printTree (strings: Array[String]): Unit = {
    val tree = new PrefixTree
    strings.foreach(tree += _)
    tree.dump
  }

  def indent (l: Int): String = "  " * l

  def generateByteRecognizer(strings: Array[String]): Unit = {
    val tree = new PrefixTree
    strings.foreach(tree += _)

    def processDown (node: tree.Node): Unit = {
      val level = node.level
      val prefixLength = node.combinedBytePrefixLength
      val value: Array[Byte] = node.value.getBytes

      val branch = if (node.isFirstSibling && (level == 0 || !node.parent.get.isWord)) s"${indent(level)}    if" else " else if"

      if (node.firstChild.isEmpty) {
        print(s"$branch (len == ${prefixLength + value.length}")
      } else {
        print(s"$branch (len >= ${prefixLength + value.length}")
      }

      for (i <- 0 until value.length) {
        val j = prefixLength + i
        if (j == 0) print(s" && data(off)==${value(i)}")
        else        print(s" && data(off+$j)==${value(i)}")
      }
      println(") {")

      if (node.firstChild.isEmpty) {  // implies isWord, no need to check length
        println(s"""${indent(level+1)}    // ${node.fullValue}""")
        println(s"""${indent(level+1)}    println("${node.fullValue}")""")

      } else {
        if (node.isWord) {
          println(s"${indent(level+1)}    if (len == ${value.length}) {")
          println(s"""${indent(level+2)}    // ${node.fullValue}""")
          println(s"""${indent(level+2)}    println("${node.fullValue}")""")
          print(s"${indent(level+1)}    }")
        }
      }
    }

    def processUp (node: tree.Node): Unit = {
      val level = node.level
      print(s"${indent(level)}    }")
      if (!node.hasNextSibling) println
    }

    println("/**")
    println(s"  * string recognizer for [${strings.mkString(",")}]")
    println("  */")
    println("object Recognizer {")
    println("  def main (args: Array[String]): Unit = {")
    println("    for (s<-args) {")
    println("      val bs = s.getBytes")
    println("      matchBytes(bs,0,bs.length)")
    println("    }")
    println("  }")

    println
    println("  // matcher generated by gov.nasa.race.tool.StringMatchGenerator")
    println("  def matchBytes(data: Array[Byte], off: Int, len: Int): Unit = {")
    tree.walkDFWrapped(processDown,processUp)
    println("  }")

    println("}")
  }

  def generateCharRecognizer(strings: Array[String]): Unit = {
    val tree = new PrefixTree
    strings.foreach(tree += _)

    def processDown (node: tree.Node): Unit = {
      val level = node.level
      val prefixLength = node.combinedCharPrefixLength
      val value: String = node.value

      val branch = if (node.isFirstSibling && (level == 0 || !node.parent.get.isWord)) s"${indent(level)}    if" else " else if"

      if (node.firstChild.isEmpty) {
        print(s"$branch (len == ${prefixLength + value.length}")
      } else {
        print(s"$branch (len >= ${prefixLength + value.length}")
      }

      for (i <- 0 until value.length) {
        val j = prefixLength + i
        if (j == 0) print(s" && data(off)=='${value.charAt(i)}'")
        else        print(s" && data(off+$j)=='${value.charAt(i)}'")
      }
      println(") {")

      if (node.firstChild.isEmpty) {  // implies isWord, no need to check length
        println(s"""${indent(level+1)}    // ${node.fullValue}""")
        println(s"""${indent(level+1)}    println("${node.fullValue}")""")

      } else {
        if (node.isWord) {
          println(s"${indent(level+1)}    if (len == ${value.length}) {")
          println(s"""${indent(level+2)}    // ${node.fullValue}""")
          println(s"""${indent(level+2)}    println("${node.fullValue}")""")
          print(s"${indent(level+1)}    }")
        }
      }
    }

    def processUp (node: tree.Node): Unit = {
      val level = node.level
      print(s"${indent(level)}    }")
      if (!node.hasNextSibling) println
    }

    println("/**")
    println(s"  * string recognizer for [${strings.mkString(",")}]")
    println("  */")
    println("object Recognizer {")
    println("  def main (args: Array[String]): Unit = {")
    println("    for (s<-args) {")
    println("      matchChars(s.toCharArray,0,s.length)")
    println("    }")
    println("  }")

    println
    println("  // matcher generated by gov.nasa.race.tool.StringMatchGenerator")
    println("  def matchChars(data: Array[Char], off: Int, len: Int): Unit = {")
    tree.walkDFWrapped(processDown,processUp)
    println("  }")

    println("}")
  }
}
