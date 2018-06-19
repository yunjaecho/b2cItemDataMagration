package com.yunjae

import cats.implicits._
import cats.effect.IO
import fs2.Stream
import doobie._
import doobie.implicits._

import scala.io.Source

case class B2CItem(itemCd: Int, condDelv: Option[Int], condTax: Option[Int], content: Option[String])

object DataBase {
  val atDb = Transactor.fromDriverManager[IO](
    "oracle.jdbc.driver.OracleDriver", "jdbc:oracle:thin:@192.168.100.62:1521:afct1", "CM", "cmqhdkscjfwj"
  )

  val posamllDb = Transactor.fromDriverManager[IO](
    "com.tmax.tibero.jdbc.TbDriver", "jdbc:tibero:thin:@192.168.100.128:8629:tibero", "ATEAT", "atqwaszx12"
  )
}


object ExcutionQuery extends  App {
  val getItemCds =  Source.fromResource("itemCd").getLines().map(_.toInt).toList

  def getB2cItems(itemCd: Int)  =
    sql"SELECT 상품코드, 조건부_배송비, 부가세면세종류,  상품상세내역 FROM CM_ITM_B2C상품정보 WHERE 상품코드 = $itemCd".query[B2CItem]
      // list 처리
      /*.stream
      .compile
      .toList*/
      .unique
      .transact(DataBase.atDb)
      .unsafeRunSync





  def inertB2CItem(item: B2CItem) = {
    val copyItem = item.copy(content = Option(item.content.getOrElse("").replaceAll("/UserFiles/", "http://www.eatmart.co.kr/UserFiles/")))
    Update[B2CItem]("INSERT INTO B2C_ITM_ORG VALUES(?, ?, ?, ?)", None)
      .updateMany(copyItem +: Nil)
      .transact(DataBase.posamllDb)
      .unsafeRunSync
  }

  getItemCds.foreach { item =>
    inertB2CItem(getB2cItems(item))
  }
}


