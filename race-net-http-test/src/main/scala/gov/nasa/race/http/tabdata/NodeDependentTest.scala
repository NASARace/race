package gov.nasa.race.http.tabdata

import gov.nasa.race.common.PathIdentifier
import gov.nasa.race.core.RaceException
import gov.nasa.race.uom.DateTime
import gov.nasa.race.util.FileUtils

import java.io.File
import scala.collection.mutable

/**
  * common functions for tests that need Node instances
  */
trait NodeDependentTest {

  def fileContentsAsBytes (pathName: String): Array[Byte] = {
    println(s"  reading file $pathName")
    FileUtils.fileContentsAsBytes(pathName).get
  }

  def getColumnData(dataDir: String, columnList: ColumnList, rowList: RowList): Map[String, ColumnData] = {
    val map = mutable.Map.empty[String, ColumnData]

    for (p <- columnList.columns) {
      val col = p._2
      val colName = PathIdentifier.name(col.id)
      val f = new File(dataDir, s"$colName.json")
      if (f.isFile) {
        val parser = new ColumnDataParser(rowList)
        parser.parse(fileContentsAsBytes(f.getPath)) match {
          case Some(cd) => map += col.id -> cd
          case None => throw new RaceException(f"error parsing column data in $f")
        }
      } else {
        println(s"no column data for '$colName'")
        // init with Date0 so that we always try to update from remote
        map += col.id -> ColumnData(col.id, DateTime.Date0, Map.empty[String, CellValue[_]])
      }
    }

    map.toMap
  }

  def getNode(nodeId: String, dataDir: String): Node = {
    val columnList = new ColumnListParser().parse(fileContentsAsBytes(dataDir + "/columnList.json")).get
    val rowList = new RowListParser(columnList.id).parse(fileContentsAsBytes(dataDir + "/rowList.json")).get
    val cds = getColumnData(dataDir, columnList, rowList)

    Node(nodeId, None, columnList, rowList, cds)
  }

}
