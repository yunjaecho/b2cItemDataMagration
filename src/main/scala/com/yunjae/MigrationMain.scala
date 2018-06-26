package com.yunjae

import java.awt.image.BufferedImage
import java.io._
import java.net.URL
import cats.implicits._
import cats.effect.IO
import com.sksamuel.scrimage.Image
import javax.imageio.ImageIO
import doobie._
import doobie.implicits._

import sys.process._
import scala.io.Source

case class B2CItem(containerId: String, itemCd: Int, condDelv: Option[Int], condTax: Option[Int], content: Option[String], imageAddr0: Option[String], imageAddr1: Option[String], imageAddr2: Option[String], imageAddr3: Option[String] )

case class B2CItemCert(itemCd: Int, ptnNo: Int, stdCertNo: String, certStatusCd: String, certGbNm: Option[String], certStartDt: Option[String], stdCertImgPath: Option[String], repItemNm: Option[String])

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
    "com.tmax.tibero.jdbc.TbDriver", "jdbc:tibero:thin:@192.168.100.136:8629:tibero", "ATEAT", "atqwaszx12"
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
    * B2C 상품인증 정보 가져오기
    * @return
    */
  def selectB2CItemCert = {
    sql"""
         |  SELECT
         |        T_A.상품코드   AS ITEM_CD,
         |        T_B.출하자코드 AS PTN_NO,
         |        T_B.인증번호   AS STD_CERT_NO,
         |        (CASE
         |             WHEN T_B.인증상태 = '6' THEN 'CRT01'
         |             WHEN T_B.인증상태 = '12' THEN 'CRT02'
         |             WHEN T_B.인증상태 = '8' OR T_B.인증상태 = '13' THEN 'CRT03'
         |             ELSE 'CRT01'
         |         END) CERT_STATUS_CD,
         |        (SELECT 중분류코드명 FROM CM_COM_코드MASTER WHERE 대분류코드 = 'C050' AND 중분류코드 = T_B.인증구분코드) AS CERT_GB_NM,
         |        T_B.최초인증일자 AS CERT_STRT_DT,
         |        T_B.인증이미지 AS STD_CERT_IMG_PATH,
         |        T_B.인증상세 AS REP_ITEM_NM
         |  FROM (
         |        SELECT
         |              B.상품코드,
         |              MAX(B."인증신청일련번호") AS 인증신청일련번호
         |        FROM  CM_ITM_B2C상품정보 A,
         |              CM_ITM_상품인증    B
         |        WHERE A.상품코드 = B.상품코드
         |          AND B.USE_YN = '1'
         |        GROUP BY B.상품코드
         |       ) T_A,
         |        CM_MBR_인증정보 T_B
         |  WHERE T_A.인증신청일련번호 = T_B.인증신청일련번호
         |    AND T_B.USE_YN = '1'
         |    AND T_B.인증번호 IS NOT NULL
       """.stripMargin
      .query[B2CItemCert]
      .stream
      .compile
      .toList
      .transact(DataBase.atDb)
      .unsafeRunSync
  }

  /**
    * 포스몰에 B2C상품인증 정보 INSERT
    * @param items
    * @return
    */
  def inertB2CItemCert(items: List[B2CItemCert]) = {
    Update[B2CItemCert]("INSERT INTO B2C_ITEM_CERT VALUES(?, ?, ?, ?, ?, ?, ?, ?)", None)
      .updateMany(items)
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
  def getContentFile(containerCategory: String): List[ContentFile]  =
    sql"""
         |SELECT
         |      CONTAINER_ID,
         |      FILE_DESCRIPTION,
         |      SAVED_PATH
         |FROM  TB_CONTENT_FILE
         |WHERE USER_ID = 'b2c'
         |  AND FILE_DESCRIPTION LIKE 'http://www.eatmart.co.kr/UserFiles%'
         |  AND CONTAINER_CATEGORY = $containerCategory
            AND USER_ID = 'b2c'
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

    // 상품 이미지 정보 저장 처리
    //getContentFile("GOODS_L").foreach(createThumbnail)

    // B2C 인증정보 가져오기
    //inertB2CItemCert(selectB2CItemCert)

    // 인증서 이미지 정보 저장 처리
    getContentFile("SELLERATTCH_CERT_DOC").foreach { file =>
      // Download the contents of a URL to a file
      // external system commands in Scala
      new URL(file.fileDescription) #> new File(s"D:/B2C_IMAGE/${file.savePath}") !!

    }
  }
}


