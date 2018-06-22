package com.yunjae

import java.awt.image.BufferedImage
import java.io._
import java.net.{HttpURLConnection, URL}

import cats.implicits._
import cats.effect.IO
import com.sksamuel.scrimage.Image
import com.sksamuel.scrimage.ScaleMethod.FastScale
import com.sksamuel.scrimage.nio.JpegWriter
import javax.imageio.ImageIO
//import fs2.Stream
import doobie._
import doobie.implicits._

import scala.io.Source

case class B2CItem(containerId: String, itemCd: Int, condDelv: Option[Int], condTax: Option[Int], content: Option[String], imageAddr0: Option[String], imageAddr1: Option[String], imageAddr2: Option[String], imageAddr3: Option[String] )

/**
  * TB_CONTENT_FILE  등록된 테이블 중에 FILE_DESCRIPTION, SAVED_PATH 정보
  * @param fileDescription
  * @param savePath
  */
case class ContentFile(containerId: String, fileDescription: String, savePath: String)

object DataBase {
  val atDb = Transactor.fromDriverManager[IO](
    "oracle.jdbc.driver.OracleDriver", "jdbc:oracle:thin:@192.168.100.62:1521:afct1", "CM", "cmqhdkscjfwj"
  )

  val posamllDb = Transactor.fromDriverManager[IO](
    "com.tmax.tibero.jdbc.TbDriver", "jdbc:tibero:thin:@192.168.100.128:8629:tibero", "ATEAT", "atqwaszx12"
  )

  val posamllStats = Transactor.fromDriverManager[IO](
    "com.tmax.tibero.jdbc.TbDriver", "jdbc:tibero:thin:@192.168.100.136:8629:tibero", "POSMALL_STATS", "POSMALL_STATS"
  )
}


object MigrationMain {
  /**
    * B2C 조회할 상품코드 정보 가져오기
    */
  val getItemCds =  Source.fromResource("itemCd").getLines().map(_.toInt).toList

  /**
    * B2C Database 상품 추가정보 조회
    * @param itemCd
    * @return
    */
  def getB2cItems(itemCd: Int)  =
    sql"SELECT 상품코드, 조건부_배송비, 부가세면세종류,  상품상세내역, 대표이미지주소, 이미지주소1, 이미지주소2 , 이미지주소3 FROM CM_ITM_B2C상품정보 WHERE 상품코드 = $itemCd".query[B2CItem]
      // list 처리
      /*.stream
      .compile
      .toList*/
      .unique
      .transact(DataBase.atDb)
      .unsafeRunSync


  /**
    * B2C에서 상품조회 한 데이터를 포스몰 테이블 INSERT
    * @param item
    * @return
    */
  def inertB2CItem(item: B2CItem) = {
    val copyItem = item.copy(content = Option(item.content.getOrElse("").replaceAll("/UserFiles/", "http://www.eatmart.co.kr/UserFiles/")))
    Update[B2CItem]("INSERT INTO B2C_ITM_ORG VALUES(?, ?, ?, ?, ?, ?, ?, ?)", None)
      .updateMany(copyItem +: Nil)
      .transact(DataBase.posamllDb)
      .unsafeRunSync
  }



  /**
    * URL 이미지 Read 해서 대,중,소 이미지 리사이징 처리
    * @param contentFile
    */
  def createThumbnail(contentFile: ContentFile) = {
    val imageAddr1 = s"D:/B2C_IMAGE/${contentFile.savePath.replaceAll("GOODS_L", "GOODS_S")}"
    val imageAddr2 = s"D:/B2C_IMAGE/${contentFile.savePath.replaceAll("GOODS_L", "GOODS_M")}"
    val imageAddr3 = s"D:/B2C_IMAGE/${contentFile.savePath}"

    val imageSizes = List((300, imageAddr1), (450, imageAddr2), (600, imageAddr3))

    val originalImage: BufferedImage = ImageIO.read(new URL(contentFile.fileDescription))

    for (size <- imageSizes) {
      // Resize
      try {
        val resized = originalImage.getScaledInstance(size._1, size._1, Image.CANONICAL_DATA_TYPE)
        val bufferedImage = new BufferedImage(size._1, size._1, BufferedImage.TYPE_INT_RGB)
        bufferedImage.getGraphics.drawImage(resized, 0, 0, null)
        ImageIO.write(bufferedImage, "JPEG", new File(size._2))
      } catch {
        case e => println(s"GD_MST_NO : ${contentFile.containerId}   ${e.getMessage}")
      }
    }
  }

  /**
    * 이미정보 조회
    * @return
    */
  def getContentFile: List[ContentFile]  =
    sql"""
         |SELECT
         |      CONTAINER_ID,
         |      FILE_DESCRIPTION,
         |      SAVED_PATH
         |FROM  TB_CONTENT_FILE
         |WHERE USER_ID = 'b2c'
         |  AND FILE_DESCRIPTION LIKE 'http://www.eatmart.co.kr/UserFiles%'
         |  AND CONTAINER_CATEGORY = 'GOODS_L'
       """.stripMargin.query[ContentFile]
      .stream
      .compile
      .toList
      .transact(DataBase.posamllStats)
      .unsafeRunSync

  def main(args: Array[String]): Unit = {
    /*getItemCds.foreach { item =>
      inertB2CItem(getB2cItems(item))
    }*/

    getContentFile.foreach(createThumbnail)

  }


}


