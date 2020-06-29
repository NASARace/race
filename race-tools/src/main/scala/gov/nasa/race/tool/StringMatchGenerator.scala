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
    def isPrefix: Boolean = firstChild.isDefined

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
          val pn = n.parent.get
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
          val pn = n.parent.get
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
      println()
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

          val n2 = new Node(ss2,true,Some(this)) // the added node - no children, no sibling
          val n1 = new Node(ss1,isWord,Some(this),firstChild,Some(n2)) // split off part of current node inherits children

          if (firstChild.isDefined) firstChild.get.reparentSiblings(Some(n1))

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

    @tailrec final def reparentSiblings (newParent: Option[Node]): Unit = {
      parent = newParent
      if (nextSibling.isDefined) nextSibling.get.reparentSiblings(newParent)
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

  def methodName (s: String): String = {
    s.replace(':','$')
  }

  def indent (l: Int): String = "  " * l

  def generateProcessor (node: PrefixTree#Node): Unit = {
    if (node.isWord) {
      val fullValue = node.fullValue
      println(s"""    @inline def process_${methodName(fullValue)} = println("$fullValue")""")
    }
  }

  def processDown (node: PrefixTree#Node): Unit = {
    val level = node.level
    val mthName = methodName(node.fullValue)

    if (node.isFirstSibling && (level == 0 || !node.parent.get.isWord)){
      println(s"${indent(level)}    if (match_$mthName) {")
    } else {
      println(s" else if (match_$mthName) {")
    }

    if (!node.isPrefix) {  // implies isWord, no need to check length
      println(s"${indent(level+1)}    process_$mthName")

    } else {
      if (node.isWord) {
        println(s"${indent(level+1)}    if (match_${mthName}_len) {")
        println(s"${indent(level+2)}    process_$mthName")
        print(s"${indent(level+1)}    }")
      }
    }
  }

  def processUp (node: PrefixTree#Node): Unit = {
    val level = node.level
    print(s"${indent(level)}    }")
    if (!node.hasNextSibling) println()
  }


  def generateByteRecognizer(strings: Array[String]): Unit = {
    val tree = new PrefixTree
    strings.foreach(tree += _)

    def generateMatcher (node: tree.Node): Unit = {
      val data = node.value.getBytes
      val len = data.length
      val prefixLen = node.combinedBytePrefixLength
      val totalLen = prefixLen + len
      val mthName = methodName(node.fullValue)

      if (node.isPrefix) {
        print(s"    @inline def match_$mthName = { len>=$totalLen")
      } else {
        print(s"    @inline def match_$mthName = { len==$totalLen")
      }

      for (i <- 0 until len) {
        val j = prefixLen + i
        if (j == 0) print(s" && data(off)==${data(i)}")
        else        print(s" && data(off+$j)==${data(i)}")
      }
      println(" }")

      if (node.isPrefix && node.isWord) {
        // note that nodes can be both prefix and word, in which case we need a matcher for the data and for the length
        println(s"    @inline def match_${mthName}_len = { len==$totalLen }")
      }
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
    println()
    println("  def matchBytes(data: Array[Byte], off: Int, len: Int): Unit = {")
    println()
    tree.walkDF(generateProcessor) // placeholders for element processing
    println()
    tree.walkDF(generateMatcher) // defines the local (inlined) matcher funcs
    println()
    tree.walkDFWrapped(processDown,processUp) // generates the nested branch expressions
    println("  }")

    println("}")
  }

  def generateCharRecognizer(strings: Array[String]): Unit = {
    val tree = new PrefixTree
    strings.foreach(tree += _)

    def generateMatcher (node: tree.Node): Unit = {
      val data = node.value.toCharArray
      val len = data.length
      val prefixLen = node.combinedCharPrefixLength
      val totalLen = prefixLen + len
      val mthName = methodName(node.fullValue)

      if (node.isPrefix) {
        print(s"    @inline def match_$mthName = { len>=$totalLen")
      } else {
        print(s"    @inline def match_$mthName = { len==$totalLen")
      }

      for (i <- 0 until len) {
        val j = prefixLen + i
        if (j == 0) print(s" && data(off)=='${data(i)}'")
        else        print(s" && data(off+$j)=='${data(i)}'")
      }
      println(" }")

      if (node.isPrefix && node.isWord) {
        // note that nodes can be both prefix and word, in which case we need a matcher for the data and for the length
        println(s"    @inline def match_${mthName}_len = { len==$totalLen }")
      }
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
    println()
    println("  def matchChars(data: Array[Char], off: Int, len: Int): Unit = {")
    println()
    tree.walkDF(generateProcessor) // placeholders for element processing
    println()
    tree.walkDF(generateMatcher) // defines the local (inlined) matcher funcs
    println()
    tree.walkDFWrapped(processDown,processUp) // generates the nested branch expressions
    println("  }")

    println("}")
  }
}
