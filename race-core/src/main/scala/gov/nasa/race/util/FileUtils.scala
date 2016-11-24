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

package gov.nasa.race.util

import java.io.{File, FileInputStream, IOException, InputStream}
import java.nio.ByteBuffer
import java.nio.charset.{CharsetDecoder, StandardCharsets}
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

import StringUtils._
import gov.nasa.race._

/**
 * common file related functions
 */
object FileUtils {

  def fileContentsAsUTF8String(file: File): Option[String] = {
    if (file.isFile) {
      Some(new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8))
    } else {
      None
    }
  }

  def fileContentsAsChars(file: File): Option[Array[Char]] = {
    if (file.isFile) {
      val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder
      val in: ByteBuffer = ByteBuffer.wrap(Files.readAllBytes(file.toPath))
      Some(decoder.decode(in).array)
    } else None
  }

  def fileContentsAsBytes(file: File): Option[Array[Byte]] = {
    if (file.isFile) Some(Files.readAllBytes(file.toPath)) else None
  }

  def existingFile(file: File, ext: String = null): Option[File] = {
    if (file.isFile) {
      return Some(file)
    } else if (ext != null) {
      val f = new File(s"${file.getPath}.$ext")
      if (f.isFile) return Some(f)
    }
    None
  }

  def isExistingNonEmptyFile(pathName: String) = existingNonEmptyFile(pathName).isDefined

  def existingNonEmptyFile(file: File, ext: String = null): Option[File] = {
    if (file.isFile) {
      if (file.length() > 0) return Some(file)
    } else if (ext != null) {
      val f = new File(s"${file.getPath}.$ext")
      if (f.isFile && f.length() > 0) return Some(f)
    }
    None
  }

  def existingNonEmptyFile(s: String): Option[File] = existingNonEmptyFile(new File(s))

  def ensureDir (pathname: String): Option[File] = {
    val dir = new File(pathname)
    if (!dir.isDirectory) {
      if (dir.mkdirs) Some(dir) else None
    } else Some(dir)
  }

  def using[T](is: InputStream)(f: (InputStream) => T): T = {
    try {
      f(is)
    } finally {
      is.close()
    }
  }

  private def hexCheckSum(file: File, msgDigest: MessageDigest): Option[String] = {
    if (file.isFile) {
      using(new FileInputStream(file)) { fis =>
        val buf = new Array[Byte](8192)
        var len = fis.read(buf)
        while (len > 0) {
          msgDigest.update(buf, 0, len)
          len = fis.read(buf)
        }
        Some(toHexString(msgDigest.digest))
      }
    } else None
  }

  def sha1CheckSum(file: File) = hexCheckSum(file, MessageDigest.getInstance("SHA-1"))
  def md5CheckSum(file: File) = hexCheckSum(file, MessageDigest.getInstance("MD5")).map(padLeft(_, 32, ' '))

  /**
   * wrapper object for java.nio.file FileSystem matchers
   */
  class FileFinder(val dir: String, val globPattern: String) extends SimpleFileVisitor[Path] {
    val fs = FileSystems.getDefault
    val matcher = fs.getPathMatcher(s"glob:$dir/$globPattern")
    val base = fs.getPath(dir)
    var matches = Seq.empty[File]

    Files.walkFileTree(base, this)

    def find(file: Path): Unit = {
      if (matcher.matches(file)) {
        matches = matches :+ file.toFile
      }
    }

    def visitPath(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
      find(file)
      FileVisitResult.CONTINUE
    }

    override def visitFile(file: Path, attrs: BasicFileAttributes) = visitPath(file, attrs)
    override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes) = visitPath(dir, attrs)
    override def visitFileFailed(file: Path, exception: IOException) = FileVisitResult.CONTINUE
  }

  def getMatchingFilesIn(dir: String, globPattern: String): Seq[File] = {
    new FileFinder(dir, globPattern).matches
  }

  def isGlobPattern(pattern: String): Boolean = {
    (pattern.indexOf('*') >= 0) || (pattern.indexOf('?') >= 0)
  }

  def relUserHomePath(file: File) = {
    file.getAbsolutePath.substring(userHome.length + 1)
  }

  def relUserDirPath(file: File) = {
    file.getAbsolutePath.substring(userDir.length + 1)
  }

  def relPath(dir: File, file: File) = {
    val dirPath = dir.getAbsolutePath + '/'
    val filePath = file.getAbsolutePath
    if (filePath.startsWith(dirPath)) filePath.substring(dirPath.length) else file.getPath
  }

  def filename(path: String) = (new File(path)).getName

  def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) {
      file.listFiles.foreach(deleteRecursively)
    }
    if (file.exists && !file.delete) {
      throw new Exception(s"deleteRecursively ${file.getAbsolutePath} failed")
    }
  }
}
